package io.iconator.core.controller.exceptions;

import io.iconator.core.controller.exceptions.constants.ExceptionConstants;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = ExceptionConstants.ETHEREUM_ADDRESS_EMPTY_REASON)
public class EthereumWalletAddressEmptyException extends BaseException {

    public EthereumWalletAddressEmptyException() {
        super(ExceptionConstants.ETHEREUM_ADDRESS_EMPTY_CODE);
    }
}
