package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

import heidtmare.dmnspwn.config.DmnProperties;
import heidtmare.dmnspwn.s3.S3ModelStore;
import heidtmare.dmnspwn.store.ModelStore;

import jakarta.servlet.http.Cookie;

import software.amazon.awssdk.services.s3.S3Client;

/** The application with models stored in S3, as when it runs as several instances. */
@SpringBootTest(properties = {"dmnspwn.storage=s3", "dmnspwn.s3.enabled=true", "dmnspwn.s3.bucket=models",
        "dmnspwn.s3.region=us-east-1", "dmnspwn.seed-samples=true"})
@AutoConfigureMockMvc
class S3StorageWebTest {

    static final FakeS3Client S3 = new FakeS3Client();

    @TestBean(name = "s3Client")
    S3Client s3Client;

    static S3Client s3Client() {
        return S3;
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ModelStore store;

    @Test
    void storesModelsInTheBucketAndKeepsMessagesAcrossInstances() throws Exception {
        assertThat(store).isInstanceOf(S3ModelStore.class);
        assertThat(S3.objects).containsKeys("dmnspwn/dish-selection.dmn", "dmnspwn/loan-eligibility.dmn");

        MockHttpServletResponse created = mvc.perform(post("/models").param("name", "Shared"))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().exists("dmn-flash")).andReturn().getResponse();
        String location = created.getRedirectedUrl();
        assertThat(S3.objects).containsKey("dmnspwn/shared.dmn");

        // The redirect may reach another instance: the message travels in the cookie, not the session.
        Cookie flash = created.getCookie("dmn-flash");
        mvc.perform(get(location).cookie(flash))
                .andExpect(content().string(containsString("Model created")))
                .andExpect(cookie().maxAge("dmn-flash", 0));
        mvc.perform(get(location)).andExpect(content().string(not(containsString("Model created"))));

        mvc.perform(post(location + "/elements").param("kind", "DECISION").param("name", "Approve"))
                .andExpect(status().is3xxRedirection());
        assertThat(S3.objects).containsKey("dmnspwn/.history/shared/000000000001.dmn");
        mvc.perform(post(location + "/undo")).andExpect(status().is3xxRedirection());
        assertThat(S3.content("dmnspwn/shared.dmn")).doesNotContain("Approve");
    }

    @Test
    void rejectsAnInvalidStorageConfiguration() {
        DmnProperties.S3 disabled = TestModels.properties(Path.of("x"), 1).s3();
        assertThatThrownBy(() -> new DmnProperties(DmnProperties.Storage.S3, Path.of("x"), false, 1, disabled))
                .hasMessageContaining("dmnspwn.s3.enabled=true");
        DmnProperties.S3 s3 = TestModels.s3Properties(DmnProperties.Storage.FILE, Path.of("x"), 1).s3();
        DmnProperties.S3 noBucket = new DmnProperties.S3(true, " ", "dmn/", "store/", null, null, false,
                s3.maxObjectSize(), s3.timeout());
        assertThatThrownBy(() -> new DmnProperties(DmnProperties.Storage.FILE, Path.of("x"), false, 1, noBucket))
                .hasMessageContaining("dmnspwn.s3.bucket must be set");
        DmnProperties.S3 overlapping = new DmnProperties.S3(true, "b", "dmn/", "dmn/store/", null, null, false,
                s3.maxObjectSize(), s3.timeout());
        assertThatThrownBy(() -> new DmnProperties(DmnProperties.Storage.S3, Path.of("x"), false, 1, overlapping))
                .hasMessageContaining("must not overlap");
    }
}
