package io.iconator.commons.amqp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.iconator.commons.amqp.model.dto.InvestorMessageDTO;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfirmationEmailMessage extends IncludeInvestorMessage {

    private String emailLinkUri;

    public ConfirmationEmailMessage() {
        super();
    }

    public ConfirmationEmailMessage(String emailLinkUri) {
        super();
        this.emailLinkUri = emailLinkUri;
    }

    public ConfirmationEmailMessage(InvestorMessageDTO investor, String emailLinkUri) {
        super(investor);
        this.emailLinkUri = emailLinkUri;
    }

    public String getEmailLinkUri() {
        return emailLinkUri;
    }

}
