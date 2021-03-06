package io.iconator.core.dto;

import io.iconator.core.utils.Constants;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.Date;

public class WhitelistEmailResponse {

    @NotNull
    @Size(max = Constants.EMAIL_CHAR_MAX_SIZE)
    private String email;

    private Date subscriptionDate;

    public WhitelistEmailResponse() {
    }

    public WhitelistEmailResponse(@NotNull @Size(max = Constants.EMAIL_CHAR_MAX_SIZE) String email, Date subscriptionDate) {
        this.email = email;
        this.subscriptionDate = subscriptionDate;
    }

    public String getEmail() {
        return email;
    }

    public Date getSubscriptionDate() {
        return subscriptionDate;
    }
}
