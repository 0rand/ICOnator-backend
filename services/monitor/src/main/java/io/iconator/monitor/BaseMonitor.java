package io.iconator.monitor;

import com.github.rholder.retry.RetryException;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import io.iconator.commons.model.CurrencyType;
import io.iconator.commons.model.db.EligibleForRefund;
import io.iconator.commons.model.db.EligibleForRefund.RefundReason;
import io.iconator.commons.model.db.Investor;
import io.iconator.commons.model.db.PaymentLog;
import io.iconator.commons.sql.dao.EligibleForRefundRepository;
import io.iconator.commons.sql.dao.InvestorRepository;
import io.iconator.commons.sql.dao.PaymentLogRepository;
import io.iconator.monitor.config.MonitorAppConfig;
import io.iconator.monitor.service.FxService;
import io.iconator.monitor.service.TokenConversionService;
import io.iconator.monitor.service.TokenConversionService.TokenDistributionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.github.rholder.retry.WaitStrategies.randomWait;

@Component
public class BaseMonitor {

    @Autowired
    private MonitorAppConfig appConfig;

    private final static Logger LOG = LoggerFactory.getLogger(BaseMonitor.class);

    protected final TokenConversionService tokenConversionService;
    protected final InvestorRepository investorRepository;
    protected final PaymentLogRepository paymentLogRepository;
    protected final EligibleForRefundRepository eligibleForRefundRepository;
    protected final FxService fxService;

    public BaseMonitor(TokenConversionService tokenConversionService,
                       InvestorRepository investorRepository,
                       PaymentLogRepository paymentLogRepository,
                       EligibleForRefundRepository eligibleForRefundRepository,
                       FxService fxService) {
        this.tokenConversionService = tokenConversionService;
        this.investorRepository = investorRepository;
        this.paymentLogRepository = paymentLogRepository;
        this.eligibleForRefundRepository = eligibleForRefundRepository;
        this.fxService = fxService;
    }

    protected boolean isTransactionUnprocessed(String txIdentifier) {
        return !paymentLogRepository.existsByTxIdentifier(txIdentifier)
                && !eligibleForRefundRepository.existsByTxIdentifier(txIdentifier);
    }

    protected void eligibleForRefund(BigInteger amount,
                                     CurrencyType currencyType,
                                     String txoIdentifier,
                                     RefundReason reason,
                                     Investor investor) {

        EligibleForRefund eligibleForRefund = new EligibleForRefund(reason, amount, currencyType, investor.getId(), txoIdentifier);
        try {
            LOG.info("Creating refund entry for transaction {}.", txoIdentifier);
            saveEligibleForRefund(eligibleForRefund);
        } catch (Exception e) {
            if (eligibleForRefundRepository.existsByTxIdentifier(txoIdentifier)) {
                LOG.info("Couldn't create refund entry because it already existed. " +
                        "I.e. transaction was already processed.", e);
            } else {
                LOG.error("Failed creating refund entry.", e);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRES_NEW)
    protected PaymentLog savePaymentLog(PaymentLog log) {
        return paymentLogRepository.saveAndFlush(log);
    }

    @Transactional(rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRES_NEW)
    protected EligibleForRefund saveEligibleForRefund(EligibleForRefund eligibleForRefund) {
        return eligibleForRefundRepository.save(eligibleForRefund);
    }

    /**
     * // TODO [claude, 2018-07-19], Finish documentation
     * This is method is not holding any of the conversoin or distribution functionality but sits in
     * this class because the method that it calls needs to be transactional and the transaction
     * behavior of spring does only work if one object calls the transactional methods of another
     * object. If this method where in the same class as the actual conversion and distribution
     * method calling that method would not lead to a transactional execution of the code.
     * @param usd
     * @param blockTime
     * @return
     * @throws Throwable
     */
    public TokenDistributionResult convertAndDistributeToTiersWithRetries(BigDecimal usd, Date blockTime)
            throws Throwable {

        if (blockTime == null) throw new IllegalArgumentException("Block time must not be null.");
        if (usd == null) throw new IllegalArgumentException("USD amount must not be null.");

        // Retry as long as there are database locking exceptions.
        Retryer<TokenDistributionResult> retryer = RetryerBuilder.<TokenDistributionResult>newBuilder()
                .retryIfExceptionOfType(OptimisticLockingFailureException.class)
                .retryIfExceptionOfType(OptimisticLockException.class)
                .withWaitStrategy(randomWait(appConfig.getTokenConversionMaxTimeWait(), TimeUnit.MILLISECONDS))
                .withStopStrategy(StopStrategies.neverStop())
                .build();

        try {
            return retryer.call(() -> tokenConversionService.convertAndDistributeToTiers(usd, blockTime));
        } catch (ExecutionException | RetryException e) {
            LOG.error("Currency to token conversion failed.", e);
            throw e.getCause();
        }
    }
}
