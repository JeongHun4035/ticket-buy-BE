package com.ticketing.ticketbuybe.global.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError.Body> tooLarge(Exception e,HttpServletRequest r) {
        return ResponseEntity.status(413).body(ApiError.body("IMAGE_TOO_LARGE","이미지 최대 5MiB, 전체 요청 최대 6MiB입니다.",r));
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ApiError.Body> forbidden(Exception e,HttpServletRequest r) {
        return ResponseEntity.status(403).body(ApiError.body("FORBIDDEN","권한이 없습니다.",r));
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError.Body> method(Exception e,HttpServletRequest r) {
        return ResponseEntity.status(405).body(ApiError.body("METHOD_NOT_ALLOWED","지원하지 않는 HTTP 메서드입니다.",r));
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError.Body> media(Exception e,HttpServletRequest r) {
        return ResponseEntity.status(415).body(ApiError.body("UNSUPPORTED_MEDIA_TYPE","API가 지원하는 Content-Type을 사용하세요.",r));
    }
    @ExceptionHandler(ApiError.class)
    ResponseEntity<ApiError.Body> domain(ApiError e, HttpServletRequest r) {
        return ResponseEntity.status(e.status()).body(ApiError.body(e.code(),e.getMessage(),r));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class,
        org.springframework.web.multipart.MultipartException.class, jakarta.validation.ConstraintViolationException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError.Body> invalid(Exception e,HttpServletRequest r) {
        return ResponseEntity.badRequest().body(ApiError.body("INVALID_REQUEST","입력값을 확인하세요.",r));
    }
    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    ResponseEntity<ApiError.Body> conflict(Exception e,HttpServletRequest r) {
        return ResponseEntity.status(409).body(ApiError.body("CONCURRENT_REQUEST","잠시 후 다시 시도하세요.",r));
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ApiError.Body> missing(Exception e,HttpServletRequest r) {
        return ResponseEntity.status(404).body(ApiError.body("NOT_FOUND","리소스를 찾을 수 없습니다.",r));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError.Body> unexpected(Exception e,HttpServletRequest r) {
        // Never log request bodies, cookies, credentials, or SQL parameter values.
        return ResponseEntity.internalServerError().body(ApiError.body("INTERNAL_ERROR","처리 중 오류가 발생했습니다.",r));
    }
}
