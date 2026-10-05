package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest(properties = {"dmnspwn.s3.enabled=true", "dmnspwn.s3.bucket=models",
        "dmnspwn.s3.prefix=dmn/", "dmnspwn.s3.region=us-east-1", "dmnspwn.seed-samples=false"})
@AutoConfigureMockMvc
class S3WebTest {

    static final FakeS3Client S3 = new FakeS3Client();

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dmnspwn.storage-directory", storage::toString);
    }

    @TestBean(name = "s3Client")
    S3Client s3Client;

    static S3Client s3Client() {
        return S3;
    }

    @Autowired
    MockMvc mvc;

    @Test
    void browsesLoadsAndPublishes() throws Exception {
        S3.store("dmn/loan.dmn", TestModels.xml("loan-eligibility"));

        mvc.perform(get("/")).andExpect(content().string(containsString("Load from S3")));
        mvc.perform(get("/s3")).andExpect(status().isOk())
                .andExpect(content().string(containsString("loan.dmn")));

        String location = mvc.perform(post("/s3/load").param("key", "dmn/loan.dmn"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        mvc.perform(get(location + "/s3")).andExpect(status().isOk())
                .andExpect(content().string(containsString("s3://models/dmn/loan.dmn")))
                .andExpect(content().string(containsString("Unchanged since last sync")));
        mvc.perform(get("/")).andExpect(content().string(containsString("title=\"dmn/loan.dmn\">S3</span>")));

        mvc.perform(post(location + "/s3/publish").param("key", "dmn/copy.dmn"))
                .andExpect(redirectedUrl(location + "/s3"))
                .andExpect(flash().attribute("success", "Published to s3://models/dmn/copy.dmn"));
        assertThat(S3.objects).containsKey("dmn/copy.dmn");

        S3.store("dmn/copy.dmn", "<other/>");
        mvc.perform(post(location + "/s3/publish").param("key", "dmn/copy.dmn"))
                .andExpect(flash().attribute("conflictKey", "dmn/copy.dmn"));
        mvc.perform(post(location + "/s3/publish").param("key", "dmn/copy.dmn").param("force", "true"))
                .andExpect(flash().attribute("success", containsString("Published")));

        mvc.perform(post(location + "/s3/publish").param("key", "elsewhere/x.dmn")
                        .header("Referer", "http://localhost" + location + "/s3"))
                .andExpect(flash().attribute("error", containsString("prefix")));
    }
}
