package io.iconator.email.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.iconator.commons.amqp.model.FundsReceivedEmailMessage;
import io.iconator.commons.mailservice.MailService;
import io.iconator.commons.model.CurrencyType;
import io.iconator.commons.model.db.Investor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

import static io.iconator.commons.amqp.model.constants.ExchangeConstants.ICONATOR_ENTRY_EXCHANGE;
import static io.iconator.commons.amqp.model.constants.QueueConstants.FUNDS_RECEIVED_EMAIL_QUEUE;
import static io.iconator.commons.amqp.model.constants.RoutingKeyConstants.FUNDS_RECEIVED_ROUTING_KEY;

@Component
public class FundsReceivedEmailMessageConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(FundsReceivedEmailMessageConsumer.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MailService mailService;

    @RabbitListener(
            bindings = @QueueBinding(value = @Queue(value = FUNDS_RECEIVED_EMAIL_QUEUE, autoDelete = "false"),
                    exchange = @Exchange(
                            value = ICONATOR_ENTRY_EXCHANGE,
                            type = ExchangeTypes.TOPIC,
                            ignoreDeclarationExceptions = "true",
                            durable = "true"
                    ),
                    key = FUNDS_RECEIVED_ROUTING_KEY)
    )
    public void receiveMessage(byte[] message) {
        LOG.debug("Received from consumer: " + new String(message));

        FundsReceivedEmailMessage fundsReceivedEmailMessage = null;
        try {
            fundsReceivedEmailMessage = objectMapper.reader().forType(FundsReceivedEmailMessage.class).readValue(message);
        } catch (IOException e) {
            LOG.error("Message not valid.");
        }

        try {
            Investor investor = fundsReceivedEmailMessage.getInvestor().toInvestor();
            BigDecimal amountFundsReceived = fundsReceivedEmailMessage.getAmountFundsReceived();
            CurrencyType currencyType = fundsReceivedEmailMessage.getCurrencyType();
            String linkToTransaction = fundsReceivedEmailMessage.getLinkToTransaction();
            BigDecimal tokenAmount = fundsReceivedEmailMessage.getTokenAmount();
            // TODO: 05.03.18 Guil:
            // Add a retry mechanism (e.g., for when the SMTP server is offline)
            mailService.sendFundsReceivedEmail(investor, amountFundsReceived, currencyType, linkToTransaction, tokenAmount);
        } catch (Exception e) {
            // TODO: 05.03.18 Guil:
            // Instead of just output the error, send to a structured log,
            // e.g., logstash or, also, send an email to an admin
            LOG.error("Email not sent.", e);
        }

    }

}
