package learning.authflow.web;

import learning.authflow.exception.AuthflowException;
import learning.authflow.web.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 认证流程异常处理器
 */
@RestControllerAdvice
public class AuthflowExceptionHandler {

    @ExceptionHandler(AuthflowException.class)
    public ResponseEntity<ErrorResponse> handleAuthflowException(AuthflowException e) {
        ErrorResponse error = new ErrorResponse();
        error.setName(e.getName());
        error.setReason(e.getReason());
        error.setMessage(e.getMessage());
        error.setCode(e.getCode());
        error.setInfo(e.getInfo());

        return ResponseEntity.status(e.getCode()).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        ErrorResponse error = new ErrorResponse();
        error.setName("BadRequest");
        error.setReason("InvalidInput");
        error.setMessage("Validation failed: " + e.getMessage());
        error.setCode(400);

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e) {
        ErrorResponse error = new ErrorResponse();
        error.setName("InternalError");
        error.setReason("UnexpectedError");
        error.setMessage(e.getMessage());
        error.setCode(500);

        return ResponseEntity.status(500).body(error);
    }
}
