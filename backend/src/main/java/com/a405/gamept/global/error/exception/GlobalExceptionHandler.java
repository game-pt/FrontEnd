package com.a405.gamept.global.error.exception;

import com.a405.gamept.global.error.dto.ErrorResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> allUncaughtHandle(Exception e) {
        log.error("allUncaughtHandle : {}", e);
        return ResponseEntity.internalServerError().build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ErrorResponseDto> methodArgumentNotValidExceptionHandle(MethodArgumentNotValidException e) {
        log.error("methodArgumentNotValidException : {}", e);
        return ResponseEntity.badRequest()
                .body(ErrorResponseDto.of(HttpStatus.BAD_REQUEST.value(), e.getFieldErrors().get(0).getDefaultMessage()));
    }
}