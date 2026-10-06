package com.pigeon.blackbox.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/*
 * Turns every invalid request into a 400 with a ProblemDetail body (RFC 9457). The parent class already
 * handles the missing parameters, the unparsable values (dates, event types) and the @Min/@Max violations.
 */
@RestControllerAdvice
class AnalyticsExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(InvalidAnalyticsQueryException.class)
	ProblemDetail handleInvalidQuery(InvalidAnalyticsQueryException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setTitle("Invalid analytics query");
		return problem;
	}
}
