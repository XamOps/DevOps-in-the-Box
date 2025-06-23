package in.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import software.amazon.awssdk.services.costexplorer.model.CostExplorerException;

@ControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(CostExplorerException.class)
    public ResponseEntity<ErrorResponse> handleAwsException(CostExplorerException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorResponse("AWS_SERVICE_ERROR", 
                "AWS service unavailable: " + ex.awsErrorDetails().errorMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("INTERNAL_ERROR", 
                "An unexpected error occurred: " + ex.getMessage()));
    }
    
    record ErrorResponse(String code, String message) {}
}