package heidtmare.dmnspwn.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Requests sent by the page scripts ({@code drd.js}), marked with {@code X-Requested-With: fetch}. The script
 * reloads the page itself, so where a form POST would be redirected the script gets 204 No Content instead;
 * failures are answered by {@link WebAdvice}.
 */
@Configuration
public class FetchRequests implements WebMvcConfigurer {

    static final String HEADER = "X-Requested-With";
    static final String VALUE = "fetch";

    static boolean isFetch(HttpServletRequest request) {
        return VALUE.equals(request.getHeader(HEADER));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                                   ModelAndView mav) {
                if (mav != null && isFetch(request) && mav.getViewName() != null
                        && mav.getViewName().startsWith("redirect:")) {
                    mav.clear();
                    response.setStatus(HttpStatus.NO_CONTENT.value());
                }
            }
        });
    }
}
