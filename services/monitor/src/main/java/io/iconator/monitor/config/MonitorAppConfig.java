package io.iconator.monitor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.math.BigInteger;

@Configuration
public class MonitorAppConfig {

    @Value("${io.iconator.services.monitor.eth.node.enabled}")
    private Boolean ethereumNodeEnabled;

    @Value("${io.iconator.services.monitor.eth.node.start-block}")
    private Long ethereumNodeStartBlock;

    @Value("${io.iconator.services.monitor.eth.node.url}")
    private String ethereumNodeUrl;

    @Value("${io.iconator.services.monitor.btc.node.enabled}")
    private Boolean bitcoinNodeEnabled;

    @Value("${io.iconator.services.monitor.retry.wait-between-attempts.max}")
    private Long tokenConversionMaxTimeWait;

    @Value("${io.iconator.services.monitor.token.usd-per-token}")
    private BigDecimal usdPerToken;

    @Value("${io.iconator.services.monitor.token.total-amount}")
    private BigDecimal totalTokenAmount;

    @Value("${io.iconator.services.monitor.token.atomic-unit-factor}")
    private int atomicUnitFactor;

    public Boolean getEthereumNodeEnabled() {
        return ethereumNodeEnabled;
    }

    public Long getEthereumNodeStartBlock() {
        return ethereumNodeStartBlock;
    }

    public String getEthereumNodeUrl() {
        return ethereumNodeUrl;
    }

    public Boolean getBitcoinNodeEnabled() {
        return bitcoinNodeEnabled;
    }

    public Long getTokenConversionMaxTimeWait() {
        return tokenConversionMaxTimeWait;
    }

    public BigDecimal getUsdPerToken() {
        return usdPerToken;
    }

    public BigDecimal getTotalTokenAmount() {
        return totalTokenAmount;
    }

    public BigInteger getAtomicUnitFactor() {
        return BigInteger.TEN.pow(atomicUnitFactor);
    }
}
