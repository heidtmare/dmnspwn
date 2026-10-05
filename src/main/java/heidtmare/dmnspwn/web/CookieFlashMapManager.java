package heidtmare.dmnspwn.web;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.AbstractFlashMapManager;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Keeps flash attributes (the messages shown after a redirect) in a cookie instead of the HTTP session, so that the
 * page after a redirect may be served by a different instance. Only text attributes are kept, and their target
 * request parameters are not, so a message is shown on the first request for its path.
 */
@Component(DispatcherServlet.FLASH_MAP_MANAGER_BEAN_NAME)
public class CookieFlashMapManager extends AbstractFlashMapManager {

    static final String COOKIE = "dmn-flash";
    /** Browsers accept about 4 KB per cookie. */
    private static final int MAX_COOKIE = 3500;
    private static final int MAX_VALUE = 1000;

    @Override
    protected List<FlashMap> retrieveFlashMaps(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, COOKIE);
        if (cookie == null || cookie.getValue().isEmpty()) {
            return null;
        }
        try {
            return decode(cookie.getValue());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    protected void updateFlashMaps(List<FlashMap> flashMaps, HttpServletRequest request,
                                   HttpServletResponse response) {
        List<FlashMap> kept = new ArrayList<>(flashMaps);
        String value = encode(kept);
        while (value.length() > MAX_COOKIE && !kept.isEmpty()) {
            kept.removeFirst();
            value = encode(kept);
        }
        String path = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
        ResponseCookie cookie = ResponseCookie.from(COOKIE, value).path(path).httpOnly(true).sameSite("Lax")
                .secure(request.isSecure()).maxAge(kept.isEmpty() ? 0 : getFlashMapTimeout()).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    static String encode(List<FlashMap> flashMaps) {
        if (flashMaps.isEmpty()) {
            return "";
        }
        Properties props = new Properties();
        for (int i = 0; i < flashMaps.size(); i++) {
            FlashMap map = flashMaps.get(i);
            if (map.getTargetRequestPath() != null) {
                props.setProperty(i + ".path", map.getTargetRequestPath());
            }
            props.setProperty(i + ".expires", Long.toString(map.getExpirationTime()));
            for (var entry : map.entrySet()) {
                if (entry.getValue() instanceof String text) {
                    props.setProperty(i + ".a." + entry.getKey(),
                            text.length() > MAX_VALUE ? text.substring(0, MAX_VALUE) + "…" : text);
                }
            }
        }
        props.setProperty("count", Integer.toString(flashMaps.size()));
        StringWriter out = new StringWriter();
        try {
            props.store(out, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    static List<FlashMap> decode(String value) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int count = Math.min(Integer.parseInt(props.getProperty("count", "0")), 20);
        List<FlashMap> maps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FlashMap map = new FlashMap();
            map.setTargetRequestPath(props.getProperty(i + ".path"));
            map.setExpirationTime(Long.parseLong(props.getProperty(i + ".expires", "0")));
            String prefix = i + ".a.";
            for (String name : props.stringPropertyNames()) {
                if (name.startsWith(prefix)) {
                    map.put(name.substring(prefix.length()), props.getProperty(name));
                }
            }
            maps.add(map);
        }
        return maps;
    }
}
