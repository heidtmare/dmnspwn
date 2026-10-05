package heidtmare.dmnspwn.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.FlashMap;

import jakarta.servlet.http.Cookie;

class CookieFlashMapManagerTest {

    @Test
    void roundTripsTextAttributes() {
        FlashMap map = new FlashMap();
        map.setTargetRequestPath("/models/m");
        map.setExpirationTime(1234);
        map.put("success", "Saved ✓ = done\nnext line");
        map.put("count", 3);

        List<FlashMap> read = CookieFlashMapManager.decode(CookieFlashMapManager.encode(List.of(map)));

        assertThat(read).singleElement().satisfies(m -> {
            assertThat(m.getTargetRequestPath()).isEqualTo("/models/m");
            assertThat(m.getExpirationTime()).isEqualTo(1234);
            assertThat((java.util.Map<String, Object>) m).containsOnlyKeys("success").containsEntry("success", "Saved ✓ = done\nnext line");
        });
    }

    @Test
    void shortensLongMessages() {
        FlashMap map = new FlashMap();
        map.put("error", "x".repeat(5000));

        String text = (String) CookieFlashMapManager.decode(CookieFlashMapManager.encode(List.of(map)))
                .getFirst().get("error");

        assertThat(text).hasSize(1001).endsWith("…");
    }

    @Test
    void ignoresCookiesItCannotRead() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CookieFlashMapManager.COOKIE, "%%%not-base64"));

        assertThat(new CookieFlashMapManager().retrieveFlashMaps(request)).isNull();
        assertThat(CookieFlashMapManager.encode(List.of())).isEmpty();
    }
}
