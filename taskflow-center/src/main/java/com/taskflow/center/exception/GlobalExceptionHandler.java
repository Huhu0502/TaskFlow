package com.taskflow.center.exception;

import com.taskflow.common.enums.ResultCode;
import com.taskflow.common.exception.BizException;
import com.taskflow.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理
 *
 * <p>HTTP 状态码约定：
 * <ul>
 *   <li>参数/格式错误 → 400（客户端请求不合法）</li>
 *   <li>业务错误（含任务不存在、幂等冲突等）→ 200（请求合法，body 里用 code 表达业务结果）</li>
 *   <li>系统异常 → 500</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常（Service 主动抛出的） */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException e) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.ok(Result.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 参数绑定/校验异常
     *
     * <p>覆盖两种：
     * <ul>
     *   <li>@Valid 校验失败（MethodArgumentNotValidException，是 BindException 的子类）</li>
     *   <li>参数类型转换失败（如日期格式错误）</li>
     * </ul>
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::toFriendlyMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", message);
        return ResponseEntity.badRequest()
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), message));
    }

    /** 兜底：其它未捕获的异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.SYSTEM_ERROR));
    }

    /**
     * 把字段错误转成对调用方友好的消息，避免暴露 Java 内部类名
     */
    private String toFriendlyMessage(FieldError fieldError) {
        // typeMismatch = 类型转换失败（如 startTime=bad-format）
        if ("typeMismatch".equals(fieldError.getCode())) {
            return fieldError.getField() + " 格式错误";
        }
        String message = fieldError.getDefaultMessage();
        return message != null ? message : fieldError.getField() + " 参数错误";
    }
}
