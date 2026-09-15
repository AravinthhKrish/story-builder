package com.lucy.storybuilder.api

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.util.UUID

/** Renders validation failures and the exceptions below as RFC 9457 `application/problem+json`. */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler()

private fun problem(
    status: HttpStatus,
    title: String,
    detail: String,
) = ProblemDetail.forStatusAndDetail(status, detail).apply { this.title = title }

class InvalidStoryException(
    detail: String,
) : ErrorResponseException(HttpStatus.BAD_REQUEST, problem(HttpStatus.BAD_REQUEST, "Invalid story request", detail), null)

class JobNotFoundException(
    id: UUID,
) : ErrorResponseException(HttpStatus.NOT_FOUND, problem(HttpStatus.NOT_FOUND, "Job not found", "No job with id $id"), null)

class VideoNotReadyException(
    detail: String,
) : ErrorResponseException(HttpStatus.CONFLICT, problem(HttpStatus.CONFLICT, "Video not ready", detail), null)
