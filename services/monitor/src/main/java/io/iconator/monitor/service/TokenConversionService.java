package io.iconator.monitor.service;

import com.github.rholder.retry.*;
import io.iconator.commons.model.db.SaleTier;
import io.iconator.commons.sql.dao.SaleTierRepository;
import io.iconator.monitor.config.MonitorAppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.OptimisticLockException;
import javax.persistence.PersistenceException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
public class TokenConversionService {

    private final static Logger LOG = LoggerFactory.getLogger(TokenConversionService.class);

    private static final MathContext MATH_CONTEXT = new MathContext(34, RoundingMode.DOWN);

    @Autowired
    private SaleTierRepository saleTierRepository;

    @Autowired
    private MonitorAppConfig appConfig;


    public BigDecimal convertCurrencyToTokens(BigDecimal currency, BigDecimal discountRate) {
        return currency.divide(BigDecimal.ONE.subtract(discountRate), MATH_CONTEXT);
    }

    public BigDecimal convertTokensToCurrency(BigDecimal tokens, BigDecimal discountRate) {
        return tokens.multiply(BigDecimal.ONE.subtract(discountRate));
    }

    /**
     * Converts the given amount of currency to tokens and updates the sale tiers accordingly.
     * E.g. if the converted amount of tokens overflows a tier's limit the tier is set inactive and
     * the next tier is activated.
     * <p>
     * Assumes a 1:1 exchange rate from currency to smallest unit of the token.
     * <p>
     * Converted amounts are rounded down to the next integer.
     *
     * @return the result of the conversion. The {@link ConversionResult} consists of
     * <ul>
     * <li>tokens: the tokens that the given amount is worth and was assigned to one or more tiers.
     * <li>overflow: amount which could not be converted into tokens because all tiers where already
     * full.
     * </ul>
     */
    public ConversionResult convertToTokensAndUpdateTiers(BigDecimal amount, Date blockTimestamp)
            throws Throwable {

        if (blockTimestamp == null) {
            throw new IllegalArgumentException("Block time stamp must not be null.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Amount must not be null.");
        }

        // Retry as long as there are  which will probably stem from concurrent
        // reads/writes to the tiers.
        Retryer<ConversionResult> retryer = RetryerBuilder.<ConversionResult>newBuilder()
                .retryIfExceptionOfType(OptimisticLockingFailureException.class)
                .retryIfExceptionOfType(OptimisticLockException.class)
                .withWaitStrategy(WaitStrategies.randomWait(
                        appConfig.getTokenConversionMaxTimeWait(),
                        TimeUnit.MILLISECONDS))
                .withStopStrategy(StopStrategies.neverStop())
                .build();

        Callable<ConversionResult> callable =
                () -> convertToTokensAndUpdateTiersInternal(amount, blockTimestamp);

        try {
            return retryer.call(callable);
        } catch (ExecutionException e) {
            LOG.error("Currency to token conversion failed.", e);
            throw e.getCause();
        } catch (RetryException e) {
            // Should never happen because of NeverStopStrategy.
        }
        return null;
    }

    @Transactional(rollbackFor = {Exception.class},
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRES_NEW)
    protected ConversionResult convertToTokensAndUpdateTiersInternal(BigDecimal amount, Date blockTime) {
        BigDecimal remainingAmount = amount;
        BigInteger tokensTotal = BigInteger.ZERO;
        Optional<SaleTier> oCurrentTier = saleTierRepository.findByIsActiveTrue();

        while (oCurrentTier.isPresent() && remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
            SaleTier currentTier = oCurrentTier.get();

            BigDecimal tokensDecimal = convertCurrencyToTokens(remainingAmount, currentTier.getDiscount());
            BigInteger tokens = tokensDecimal.toBigInteger();

            if (tokensFillOrExceedTiersCap(tokens, currentTier)) {
                // Tokens must be distributed over multiple tiers
                // Calculate the amount that is assigned to the current tier and what remains for
                // the next tier.
                BigInteger tokensToCurrentTier = currentTier.getTokenMax().subtract(currentTier.getTokensSold());
                currentTier.setTokensSold(currentTier.getTokensSold().add(tokensToCurrentTier));
                tokensTotal = tokensTotal.add(tokensToCurrentTier);
                remainingAmount = convertTokensToCurrency(
                        tokensDecimal.subtract(new BigDecimal(tokensToCurrentTier)),
                        currentTier.getDiscount());

                currentTier.setEndDate(blockTime);
                currentTier.setInactive();
                currentTier = saleTierRepository.save(oCurrentTier.get());
                oCurrentTier = getNextTierAndSetActive(blockTime, currentTier);
            } else {
                // All tokens can be retrieved from the same tier.
                currentTier.setTokensSold(currentTier.getTokensSold().add(tokens));
                tokensTotal = tokensTotal.add(tokens);
                remainingAmount = BigDecimal.ZERO;
                saleTierRepository.save(oCurrentTier.get());
            }
        }
        return new ConversionResult(tokensTotal, remainingAmount);
    }

    /**
     * Assumes that the tiers' numbers (tierNo) are consecutive and increment by 1.
     * TODO 20.05.18 Claude:
     * Use start date to find next tier instead of using tiers' numbers.
     */
    private Optional<SaleTier> getNextTierAndSetActive(Date blockTime, SaleTier currentTier) {
        Optional<SaleTier> oNextTier = saleTierRepository.findByTierNo(currentTier.getTierNo() + 1);
        if (oNextTier.isPresent()) {
            SaleTier nextTier = oNextTier.get();
            nextTier.setStartDate(blockTime);
            nextTier.setActive();
            oNextTier = Optional.ofNullable(saleTierRepository.save(nextTier));
        }
        return oNextTier;
    }

    private boolean tokensFillOrExceedTiersCap(BigInteger tokens, SaleTier tier) {
        return tier.getTokensSold().add(tokens).compareTo(tier.getTokenMax()) >= 0;
    }

    public static class ConversionResult {
        private BigInteger tokens;
        private BigDecimal overflow;

        private ConversionResult(BigInteger tokens, BigDecimal overflow) {
            this.tokens = tokens;
            this.overflow = overflow;
        }

        public boolean hasOverflow() {
            return overflow.compareTo(BigDecimal.ZERO) > 0;
        }

        public BigInteger getTokens() {
            return tokens;
        }

        public BigDecimal getOverflow() {
            return overflow;
        }
    }
}
