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
import heidtmare.dmnspwn.store.ModelNotFoundException;
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

    @ExceptionHandler(ModelNotFoundException.class)
    public String notFound(ModelNotFoundException e, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        model.addAttribute("status", 404);
        model.addAttribute("message", e.getMessage());
        return "error";
    }

    /** Invalid edits on a POST go back to the page they came from with a message. */
    @ExceptionHandler({DmnEditException.class, DmnFormatException.class, MaxUploadSizeExceededException.class})
    public String invalid(Exception e, HttpServletRequest request, HttpServletResponse response, Model model) {
        String message = e instanceof MaxUploadSizeExceededException ? "The uploaded file is too large" : e.getMessage();
        if ("POST".equals(request.getMethod())) {
            if (FetchRequests.isFetch(request)) {
                response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
                model.addAttribute("status", 422);
                model.addAttribute("message", message);
                return "error";
            }
            FlashMap flash = RequestContextUtils.getOutputFlashMap(request);
            flash.put("error", message);
            return "redirect:" + sameOriginPath(request);
        }
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("status", 400);
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
