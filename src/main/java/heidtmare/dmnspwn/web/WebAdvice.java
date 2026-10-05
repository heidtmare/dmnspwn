package heidtmare.dmnspwn.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.RequestContextUtils;

import heidtmare.dmnspwn.config.DmnProperties;
import heidtmare.dmnspwn.edit.DmnEditException;
import heidtmare.dmnspwn.s3.S3StoreException;
import heidtmare.dmnspwn.scenario.InvalidScenarioException;
import heidtmare.dmnspwn.store.NotFoundException;
import heidtmare.dmnspwn.store.StoreConflictException;
import heidtmare.dmnspwn.xml.DmnFormatException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Shared model attributes (edit mode, current URL) and error handling for all pages. */
@ControllerAdvice
public class WebAdvice {

    public static final String EDIT_COOKIE = "dmn-edit";

    private final boolean s3Enabled;

    public WebAdvice(DmnProperties properties) {
        this.s3Enabled = properties.s3().enabled();
    }

    @ModelAttribute("s3Enabled")
    public boolean s3Enabled() {
        return s3Enabled;
    }

    @ModelAttribute("editMode")
    public boolean editMode(@CookieValue(name = EDIT_COOKIE, defaultValue = "false") boolean edit) {
        return edit;
    }

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        String query = request.getQueryString();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return query == null ? path : path + "?" + query;
    }

    @ModelAttribute("contextPath")
    public String contextPath(HttpServletRequest request) {
        return request.getContextPath();
    }

    @ExceptionHandler(NotFoundException.class)
    public String notFound(NotFoundException e, Model model, HttpServletResponse response) {
        return errorPage(HttpStatus.NOT_FOUND, e.getMessage(), model, response);
    }

    /** Invalid edits or uploads: a POST goes back to the page it came from with a message. */
    @ExceptionHandler({DmnEditException.class, DmnFormatException.class, InvalidScenarioException.class})
    public String invalid(RuntimeException e, HttpServletRequest request, HttpServletResponse response, Model model) {
        return failed(HttpStatus.BAD_REQUEST, e.getMessage(), request, response, model);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest request, HttpServletResponse response, Model model) {
        return failed(HttpStatus.BAD_REQUEST, "The uploaded file is too large", request, response, model);
    }

    /** S3 failures are the remote store's, not the request's: pages that cannot load report a bad gateway. */
    @ExceptionHandler(S3StoreException.class)
    public String s3Failed(S3StoreException e, HttpServletRequest request, HttpServletResponse response, Model model) {
        return failed(HttpStatus.BAD_GATEWAY, e.getMessage(), request, response, model);
    }

    /** Another instance kept changing the model while this change was being applied. */
    @ExceptionHandler(StoreConflictException.class)
    public String conflict(StoreConflictException e, HttpServletRequest request, HttpServletResponse response,
                           Model model) {
        return failed(HttpStatus.CONFLICT, e.getMessage(), request, response, model);
    }

    /**
     * A failed POST goes back to the page it came from with the message (fetch requests get a 422 fragment);
     * a failed GET shows the error page with {@code status}.
     */
    private static String failed(HttpStatus status, String message, HttpServletRequest request,
                                 HttpServletResponse response, Model model) {
        if ("POST".equals(request.getMethod())) {
            if (FetchRequests.isFetch(request)) {
                return errorPage(HttpStatus.UNPROCESSABLE_CONTENT, message, model, response);
            }
            FlashMap flash = RequestContextUtils.getOutputFlashMap(request);
            flash.put("error", message);
            return "redirect:" + sameOriginPath(request);
        }
        return errorPage(status, message, model, response);
    }

    private static String errorPage(HttpStatus status, String message, Model model, HttpServletResponse response) {
        response.setStatus(status.value());
        model.addAttribute("status", status.value());
        model.addAttribute("message", message);
        return "error";
    }

    /** The Referer reduced to a local path, so redirects can never leave the application. */
    static String sameOriginPath(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer != null) {
            try {
                URI uri = URI.create(referer);
                if (uri.getHost() == null || uri.getHost().equals(request.getServerName())) {
                    String path = uri.getRawPath();
                    String ctx = request.getContextPath();
                    if (path != null && path.startsWith(ctx + "/")) {
                        path = path.substring(ctx.length());
                        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return "/";
    }

    /** Validates a caller-provided return path. */
    static String safeLocalPath(String path, String fallback) {
        return path != null && path.startsWith("/") && !path.startsWith("//") && !path.contains("\\")
                ? path : fallback;
    }
}
