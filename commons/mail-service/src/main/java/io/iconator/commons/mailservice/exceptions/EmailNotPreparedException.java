package io.iconator.commons.mailservice.exceptions;

public class EmailNotPreparedException extends BaseEmailException {

    public EmailNotPreparedException() {
    }

    public EmailNotPreparedException(String message) {
        super(message);
    }

    public EmailNotPreparedException(String message, Throwable cause) {
        super(message, cause);
    }

    public EmailNotPreparedException(Throwable cause) {
        super(cause);
    }

}
