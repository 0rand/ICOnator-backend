package io.iconator.monitor;

import io.iconator.commons.amqp.model.FundsReceivedEmailMessage;
import io.iconator.commons.amqp.service.ICOnatorMessageService;
import io.iconator.commons.model.CurrencyType;
import io.iconator.commons.model.db.EligibleForRefund;
import io.iconator.commons.model.db.Investor;
import io.iconator.commons.model.db.PaymentLog;
import io.iconator.commons.sql.dao.EligibleForRefundRepository;
import io.iconator.commons.sql.dao.InvestorRepository;
import io.iconator.commons.sql.dao.PaymentLogRepository;
import io.iconator.monitor.service.FxService;
import io.iconator.monitor.service.TokenConversionService;
import io.iconator.monitor.service.exceptions.USDETHFxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthBlock.Block;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.utils.Convert;
import org.web3j.utils.Convert.Unit;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import static io.iconator.commons.amqp.model.utils.MessageDTOHelper.build;
import static java.time.temporal.ChronoUnit.MINUTES;

public class EthereumMonitor extends BaseMonitor {

    private final static Logger LOG = LoggerFactory.getLogger(EthereumMonitor.class);

    private final Web3j web3j;
    private boolean started = false;
    private Map<String, String> monitoredAddresses = new HashMap<>(); // public key -> address

    private ICOnatorMessageService messageService;

    public EthereumMonitor(FxService fxService,
                           Web3j web3j,
                           InvestorRepository investorRepository,
                           PaymentLogRepository paymentLogRepository,
                           TokenConversionService tokenConversionService,
                           EligibleForRefundRepository eligibleForRefundRepository,
                           ICOnatorMessageService messageService) {

        super(tokenConversionService, investorRepository, paymentLogRepository,
                eligibleForRefundRepository, fxService);

        this.web3j = web3j;
        this.messageService = messageService;
    }

    /**
     * Add a public key we want to monitor
     *
     * @param publicKey Ethereum public key as hex string
     */
    public synchronized void addMonitoredEtherPublicKey(String publicKey) {
        String addressString = Hex.toHexString(org.ethereum.crypto.ECKey.fromPublicOnly(Hex.decode(publicKey)).getAddress());
        if (!addressString.startsWith("0x"))
            addressString = "0x" + addressString;
        LOG.info("Add monitored Ethereum Address: {}", addressString);
        monitoredAddresses.put(addressString.toLowerCase(), publicKey);
    }

    public void start(Long startBlock) throws IOException {
        if (!started) {
            // Check if node is up-to-date
            BigInteger blockNumber = web3j.ethBlockNumber().send().getBlockNumber();
            Block highestBlock = web3j.ethGetBlockByNumber(() -> new DefaultBlockParameterNumber(blockNumber).getValue(), false).send().getBlock();
            Instant latestBlockTime = Instant.ofEpochSecond(highestBlock.getTimestamp().longValue());
            LOG.info("Highest ethereum block number from fullnode: {}. Time: {}", blockNumber, latestBlockTime);
            if (latestBlockTime.isBefore(Instant.now().minus(10, MINUTES))) {
                LOG.warn("Ethereum fullnode does not seem to be up-to-date");
            } else {
                LOG.info("Ethereum fullnode seems to be up-to-date");
            }

            started = true;

            web3j.catchUpToLatestAndSubscribeToNewBlocksObservable(
                    new DefaultBlockParameterNumber(startBlock), false)
                    .subscribe(block -> {
                        LOG.info("Processing block number: {}", block.getBlock().getNumber());
                    });

            web3j.catchUpToLatestAndSubscribeToNewTransactionsObservable(
                    new DefaultBlockParameterNumber(startBlock))
                    .subscribe(tx -> {

                        if (monitoredAddresses.containsKey(tx.getTo())
                                && isTransactionUnprocessed(tx.getHash())) {

                            try {
                                processTransaction(tx);
                            } catch (Throwable e) {
                                LOG.error("Error while processing transaction.", e);
                            }
                        }

                        if (monitoredAddresses.get(tx.getFrom().toLowerCase()) != null) {
                            // This should normally not happen as it means funds are stolen!
                            LOG.error("ATTENTION: Removed: {} wei from pay-in address", tx.getValue().toString());
                        }
                    }, throwable -> {
                        LOG.error("Error during scanning of txs: ", throwable);
                    });
        } else {
            LOG.warn("io.iconator.monitor.EthereumMonitor is already started");
        }
    }

    private void processTransaction(Transaction tx) {
        final String txIdentifier = tx.getHash();
        final String receivingAddress = tx.getTo();
        final BigInteger wei = tx.getValue();
        final long blockHeight = tx.getBlockNumber().longValue();

        LOG.debug("Detected funds received: wei {}, receiving address {}, transaction hash {}, " +
                "blockHeight {}.", wei, receivingAddress, txIdentifier, blockHeight);

        Investor investor;
        try {
            String publicKey = monitoredAddresses.get(receivingAddress);
            investor = investorRepository.findOptionalByPayInBitcoinPublicKey(publicKey).get();
        } catch (NoSuchElementException e) {
            LOG.error("Couldn't fetch investor for receiver address {}. Can't process transaction " +
                    "without knowing the associated investor. Transaction {} must be refunded",
                    receivingAddress, txIdentifier, e);
            handleMissingInvestor(wei, txIdentifier);
            return;
        }

        Date timestamp;
        try {
            Request<?, EthBlock> ethBlockRequest = web3j.ethGetBlockByNumber(
                    new DefaultBlockParameterNumber(tx.getBlockNumber()),
                    false);
            EthBlock blockRequest = ethBlockRequest.send();
            timestamp = new Date(blockRequest.getBlock().getTimestamp().longValue());
        } catch (Exception e) {
            LOG.error("Failed fetching block timestamp for transaction {}. Setting timestamp to " +
                    "current time for the entry in the payment log.", txIdentifier);
            timestamp = new Date();
        }

        BigDecimal USDperETH, usdReceived, ethers;
        try {
            USDperETH = fxService.getUSDperETH(blockHeight);
            LOG.debug("FX Service USDperETH {}, hash {}, address {}", USDperETH.toPlainString(), txIdentifier, receivingAddress);
            ethers = Convert.fromWei(new BigDecimal(wei), Unit.ETHER);
            usdReceived = ethers.multiply(USDperETH);
        } catch (USDETHFxException e) {
            LOG.error("Couldn't get US dollar price per Ether for transaction {}. " +
                    "Transaction must be refunded.", txIdentifier, e);
            handleMissingFxRate(wei, txIdentifier, investor);
            return;
        } catch (RuntimeException e) {
            LOG.error("Failed to fetch payment amount in US dollars for transaction {}. " +
                    "Transaction must be refunded.", txIdentifier, e);
            handleFailedConversionToUsd(wei, txIdentifier, investor);
            return;
        }

        LOG.debug("USD {} to be converted to tokens, for transaction {}",
                usdReceived.toPlainString(), txIdentifier);
        PaymentLog paymentLog = paymentLogRepository.save(
                new PaymentLog(
                        txIdentifier,
                        new Date(),
                        timestamp,
                        CurrencyType.ETH,
                        new BigDecimal(wei),
                        USDperETH,
                        usdReceived,
                        investor,
                        BigDecimal.ZERO));

        TokenConversionService.ConversionResult conversionResult;
        try {
            conversionResult = tokenConversionService.convertToTokensAndUpdateTiers(usdReceived, timestamp);
        } catch (Throwable e) {
            LOG.error("Failed to convert payment to tokens for transaction {}. " +
                    "Transaction must be refunded.", txIdentifier, e);
            handleFailedTokenConversion(wei, txIdentifier, investor);
            return;
        }
        BigDecimal tokenAmount = new BigDecimal(conversionResult.getTokens());
        paymentLog.setTokenAmount(tokenAmount);
        if (conversionResult.hasOverflow()) {
            LOG.info("Final tier is full. Overflow will be refunded for Transaction {}", txIdentifier);
            handleFinalTierOverflow(conversionResult.getOverflow(), USDperETH, txIdentifier, investor);
        }

        final String etherscanLink = "https://etherscan.io/tx/" + txIdentifier;

        messageService.send(new FundsReceivedEmailMessage(
                build(investor),
                new BigDecimal(wei),
                CurrencyType.ETH,
                etherscanLink,
                tokenAmount));

        LOG.info("Pay-in received: {} ETH / {} USD / {} FX / {} / Time: {} / Address: {} / " +
                        "Tokens Amount {}",
                ethers,
                paymentLog.getPaymentAmount(),
                paymentLog.getFxRate(),
                investor.getEmail(),
                paymentLog.getCreateDate(),
                receivingAddress,
                paymentLog.getTokenAmount());
    }

    private void handleMissingFxRate(BigInteger wei, String txIdentifier, Investor investor) {
        eligibleForRefundInWei(
                EligibleForRefund.RefundReason.MISSING_FX_RATE,
                wei,
                txIdentifier,
                investor);
    }

    private void handleMissingInvestor(BigInteger wei, String txIdentifier) {
        eligibleForRefundInWei(
                EligibleForRefund.RefundReason.NO_INVESTOR_FOUND_FOR_RECEIVING_ADDRESS,
                wei,
                txIdentifier,
                null);
    }

    private void handleFailedConversionToUsd(BigInteger wei, String txIdentifier, Investor investor) {
        eligibleForRefundInSatoshi(
                EligibleForRefund.RefundReason.FAILED_CONVERSION_TO_USD,
                wei,
                txIdentifier,
                investor);
    }

    private void handleFailedTokenConversion(BigInteger wei, String txIdentifier, Investor investor) {
        eligibleForRefundInSatoshi(
                EligibleForRefund.RefundReason.TOKEN_CONVERSION_FAILED,
                wei,
                txIdentifier,
                investor);
    }

    private void handleFinalTierOverflow(BigDecimal usd, BigDecimal usdPerEth, String txIdentifier,
                                         Investor investor) {

        BigInteger wei = convertUsdToWei(usd, usdPerEth);
        eligibleForRefundInSatoshi(
                EligibleForRefund.RefundReason.FINAL_TIER_OVERFLOW,
                wei,
                txIdentifier,
                investor);
    }

    private static BigInteger convertUsdToWei(BigDecimal usd, BigDecimal usdPerEth) {
        BigDecimal ethers = usd.divide(usdPerEth, new MathContext(34, RoundingMode.DOWN));
        return Convert.toWei(ethers, Unit.ETHER).toBigInteger();
    }
}
