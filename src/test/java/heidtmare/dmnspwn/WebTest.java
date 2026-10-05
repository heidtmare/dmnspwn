package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import heidtmare.dmnspwn.store.ModelService;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class WebTest {

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dmnspwn.storage-directory", storage::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ModelService models;

    private static final Cookie EDIT = new Cookie("dmn-edit", "true");

    @Test
    void rendersEveryPageOfTheSamples() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(containsString("Loan Eligibility")));
        for (String url : new String[] {"/models/loan-eligibility", "/models/dish-selection",
                "/models/loan-eligibility/elements/Loan_Offer", "/models/loan-eligibility/elements/Risk_Category",
                "/models/loan-eligibility/elements/Installment_Calculation",
                "/models/loan-eligibility/elements/Eligibility_Service", "/models/loan-eligibility/elements/Note_Minors",
                "/models/loan-eligibility/elements/Lending_Policy", "/models/dish-selection/elements/Seasonal_Menu",
                "/models/loan-eligibility/elements/Risk_Category/logic",
                "/models/loan-eligibility/elements/Installment_Calculation/logic",
                "/models/loan-eligibility/elements/Loan_Offer/logic", "/models/loan-eligibility/types",
                "/models/loan-eligibility/types/2", "/models/loan-eligibility/types/2.1",
                "/models/loan-eligibility/source", "/models/loan-eligibility/validation",
                "/models/loan-eligibility/evaluate", "/models/dish-selection/evaluate?decision=Dish"}) {
            mvc.perform(get(url)).andExpect(status().isOk());
            mvc.perform(get(url).cookie(EDIT)).andExpect(status().isOk());
        }
        mvc.perform(get("/models/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/models/loan-eligibility/elements/Missing")).andExpect(status().isNotFound());
        mvc.perform(get("/models/loan-eligibility/elements/Note_Minors/logic")).andExpect(status().isNotFound());
        mvc.perform(get("/models/loan-eligibility/types/99")).andExpect(status().isNotFound());
        mvc.perform(get("/s3")).andExpect(status().isNotFound());
        mvc.perform(get("/models/loan-eligibility")).andExpect(content().string(not(containsString("/s3\""))));
    }

    @Test
    void exposesHealthProbesOnly() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(content().string(containsString("\"UP\"")));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test
    void rendersDiagramServerSide() throws Exception {
        mvc.perform(get("/models/loan-eligibility"))
                .andExpect(content().string(containsString("<svg")))
                .andExpect(content().string(containsString("marker-end=\"url(#m-knowledge)\"")))
                .andExpect(content().string(containsString("viewBox=")))
                .andExpect(content().string(not(containsString("drd.js"))));
        mvc.perform(get("/models/loan-eligibility").cookie(EDIT))
                .andExpect(content().string(containsString("drd.js")))
                .andExpect(content().string(containsString("data-editable=\"true\"")));
        mvc.perform(get("/models/loan-eligibility/diagram.svg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("image/svg+xml")))
                .andExpect(content().string(containsString("<?xml")));
    }

    @Test
    void createsEditsAndUndoes() throws Exception {
        String location = mvc.perform(post("/models").param("name", "Web Test"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        String id = location.substring("/models/".length());

        mvc.perform(post(location + "/elements").param("kind", "INPUT_DATA").param("name", "Age"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post(location + "/elements").param("kind", "DECISION").param("name", "Adult"))
                .andExpect(status().is3xxRedirection());
        var reader = models.reader(id);
        String age = reader.elements().getFirst().id();
        String adult = reader.elements().get(1).id();

        mvc.perform(post(location + "/connections").param("source", age).param("target", adult)
                        .header("X-Requested-With", "fetch"))
                .andExpect(status().isNoContent());
        assertThat(models.reader(id).element(adult).orElseThrow().requires()).hasSize(1);
        mvc.perform(post(location + "/connections").param("source", adult).param("target", age)
                        .header("Referer", "http://localhost" + location))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("does not allow")));

        mvc.perform(post(location + "/elements/" + adult + "/logic/type").param("type", "DECISION_TABLE"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post(location + "/elements/" + adult + "/logic/decision-table")
                        .param("hitPolicy", "FIRST")
                        .param("inputs[0].expression", "Age").param("inputs[0].typeRef", "number")
                        .param("outputs[0].typeRef", "boolean")
                        .param("rules[0].inputs[0]", ">= 18").param("rules[0].outputs[0]", "true")
                        .param("action", "addRule:0"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get(location + "/elements/" + adult + "/logic").cookie(EDIT))
                .andExpect(content().string(containsString("&gt;= 18")))
                .andExpect(content().string(containsString("rules[1].inputs[0]")));

        mvc.perform(post(location + "/elements/" + adult + "/bounds").param("x", "400").param("y", "100")
                .header("X-Requested-With", "fetch")).andExpect(status().isNoContent());
        assertThat(models.xml(id)).contains("x=\"400\" y=\"100\"");

        mvc.perform(post(location + "/undo")).andExpect(flash().attribute("success", "Last change undone"));
        assertThat(models.xml(id)).doesNotContain("x=\"400\" y=\"100\"").contains("&gt;= 18");

        mvc.perform(post(location + "/elements/" + age + "/delete")).andExpect(status().is3xxRedirection());
        assertThat(models.reader(id).elements()).hasSize(1);

        mvc.perform(post(location + "/source").param("xml", "<broken")
                        .header("Referer", "http://localhost" + location + "/source"))
                .andExpect(flash().attribute("error", containsString("Malformed XML")));

        mvc.perform(post(location + "/delete")).andExpect(status().is3xxRedirection());
        mvc.perform(get(location)).andExpect(status().isNotFound());
    }

    @Test
    void uploadsAndDownloads() throws Exception {
        byte[] bytes = TestModels.xml("dish-selection").getBytes(StandardCharsets.UTF_8);
        String location = mvc.perform(multipart("/models/upload")
                        .file(new MockMultipartFile("file", "dish.dmn", "application/xml", bytes)))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        mvc.perform(get(location + "/download"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().bytes(bytes));
        mvc.perform(multipart("/models/upload")
                        .file(new MockMultipartFile("file", "x.dmn", "application/xml", "<nope/>".getBytes()))
                        .header("Referer", "http://localhost/"))
                .andExpect(flash().attribute("error", containsString("Not a DMN model")));
    }

    @Test
    void evaluatesDecisionsAndRemembersInputs() throws Exception {
        String before = models.xml("dish-selection");
        var session = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/models/dish-selection/evaluate").session(session)
                        .param("in.Season", "\"Winter\"").param("in.Guest_Count", "8")
                        .param("in.Guests_With_Children", "false").param("decision", "Dish")
                        .param("expression", "Party Size + \" party\""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&quot;Roastbeef&quot;")))
                .andExpect(content().string(containsString("&quot;medium party&quot;")))
                .andExpect(content().string(containsString("Matched rule 2")))
                .andExpect(content().string(containsString("class=\"dt-hit\"")))
                .andExpect(content().string(containsString("id=\"result-Dish\"")))
                .andExpect(content().string(containsString("href=\"#result-Dish\"")))
                .andExpect(content().string(containsString("href=\"#in-Season\"")))
                .andExpect(content().string(containsString("class=\"badge-text\"")))
                .andExpect(content().string(containsString("node node-decision ev ev-ok")))
                .andExpect(content().string(containsString("node node-decision ev-skipped")))
                .andExpect(content().string(containsString("edge edge-information active")));
        mvc.perform(get("/models/dish-selection/evaluate").session(session))
                .andExpect(content().string(containsString("value=\"&quot;Winter&quot;\"")));
        mvc.perform(post("/models/dish-selection/evaluate").param("in.Guest_Count", "8 +"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("msg-error")));
        assertThat(models.xml("dish-selection")).isEqualTo(before);
    }

    @Test
    void savesEvaluationsAsTestsAndRerunsThemAfterEdits() throws Exception {
        String id = models.importXml("dish.dmn", TestModels.xml("dish-selection"));
        String tests = "/models/" + id + "/tests";
        mvc.perform(get(tests)).andExpect(status().isOk()).andExpect(content().string(containsString("No tests yet")));

        mvc.perform(post(tests).param("in.Season", "\"Fall\"").param("in.Guest_Count", "3")
                        .param("in.Guests_With_Children", "").param("decision", "Dish").param("name", "Fall dinner"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Test saved"));
        assertThat(storage.resolve(id + ".tests.xml")).exists();
        mvc.perform(get(tests))
                .andExpect(content().string(containsString("Fall dinner")))
                .andExpect(content().string(containsString("1 test passed")))
                .andExpect(content().string(containsString("class=\"count ok\"")));
        mvc.perform(post(tests).param("in.Guest_Count", "8 +").param("name", "bad"))
                .andExpect(flash().attribute("error", containsString("Fix the input values")));

        models.replaceSource(id, models.xml(id).replace("<text>\"Spareribs\"</text></outputEntry>",
                "<text>\"Ribs\"</text></outputEntry>"));
        mvc.perform(get("/models/" + id))
                .andExpect(content().string(containsString("title=\"Failing tests\">1</span>")));
        mvc.perform(get(tests))
                .andExpect(content().string(containsString("1 of 1 failed")))
                .andExpect(content().string(containsString("&quot;Ribs&quot;")));

        mvc.perform(get("/models/" + id + "/evaluate").param("test", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"&quot;Fall&quot;\"")))
                .andExpect(content().string(containsString("&quot;Ribs&quot;")))
                .andExpect(content().string(containsString("1 of 1 expected results do not match")))
                .andExpect(content().string(containsString("expected by test")))
                .andExpect(content().string(containsString("node node-decision ev ev-mismatch")));

        mvc.perform(post(tests + "/0/accept")).andExpect(status().is3xxRedirection());
        mvc.perform(get(tests)).andExpect(content().string(containsString("1 test passed")));

        String xml = mvc.perform(get(tests + "/download"))
                .andExpect(header().string("Content-Disposition", containsString(id + ".tests.xml")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(xml).contains("<expected>").contains("Ribs");

        mvc.perform(post(tests + "/0/delete")).andExpect(status().is3xxRedirection());
        assertThat(storage.resolve(id + ".tests.xml")).doesNotExist();
        mvc.perform(multipart(tests + "/import").file(new MockMultipartFile("file", "t.xml", "application/xml",
                        xml.replace("Fall dinner", "Imported").getBytes(StandardCharsets.UTF_8))))
                .andExpect(flash().attribute("success", "1 test imported"));
        mvc.perform(get(tests)).andExpect(content().string(containsString("Imported")));
        mvc.perform(multipart(tests + "/import").file(new MockMultipartFile("file", "t.xml", "application/xml",
                        "<nope/>".getBytes(StandardCharsets.UTF_8))))
                .andExpect(flash().attribute("error", containsString("<testCases>")));
        mvc.perform(post(tests + "/7/delete")).andExpect(flash().attribute("error", "The test no longer exists"));

        java.nio.file.Files.writeString(storage.resolve(id + ".tests.xml"), "not xml");
        mvc.perform(get("/models/" + id)).andExpect(status().isOk());
        mvc.perform(get(tests)).andExpect(content().string(containsString("cannot be read")));

        models.delete(id);
        assertThat(storage.resolve(id + ".tests.xml")).doesNotExist();
    }

    @Test
    void modeToggleOnlyRedirectsLocally() throws Exception {
        mvc.perform(post("/mode").param("edit", "true").param("back", "//evil.example"))
                .andExpect(header().string("Location", "/"))
                .andExpect(header().string("Set-Cookie", containsString("dmn-edit=true")));
        mvc.perform(post("/mode").param("edit", "false").param("back", "/models/loan-eligibility"))
                .andExpect(header().string("Location", "/models/loan-eligibility"));
    }
}
