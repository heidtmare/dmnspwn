package heidtmare.dmnspwn.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import heidtmare.dmnspwn.edit.DmnEditException;
import heidtmare.dmnspwn.s3.S3Sync;
import heidtmare.dmnspwn.store.ModelService;

import jakarta.servlet.http.HttpServletResponse;

@Controller
public class HomeController {

    private final ModelService models;
    /** Present when the S3 integration is enabled. */
    private final ObjectProvider<S3Sync> s3;

    public HomeController(ModelService models, ObjectProvider<S3Sync> s3) {
        this.models = models;
        this.s3 = s3;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("models", models.list());
        S3Sync sync = s3.getIfAvailable();
        model.addAttribute("s3Links", sync == null ? Map.of() : sync.linkedKeys());
        return "index";
    }

    @PostMapping("/models")
    public String create(@RequestParam(required = false) String name, RedirectAttributes flash) {
        String id = models.create(name);
        flash.addFlashAttribute("success", "Model created");
        return "redirect:/models/" + id;
    }

    @PostMapping("/models/upload")
    public String upload(@RequestParam("file") MultipartFile file, RedirectAttributes flash) throws IOException {
        if (file.isEmpty()) {
            throw new DmnEditException("Choose a .dmn file to upload");
        }
        String id = models.importXml(file.getOriginalFilename(), new String(file.getBytes(), StandardCharsets.UTF_8));
        flash.addFlashAttribute("success", "Imported " + file.getOriginalFilename());
        return "redirect:/models/" + id;
    }

    /** Toggles the editor controls; stored in a cookie so every page renders consistently. */
    @PostMapping("/mode")
    public String mode(@RequestParam boolean edit, @RequestParam(required = false) String back,
                       HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(WebAdvice.EDIT_COOKIE, String.valueOf(edit))
                .path("/").httpOnly(true).sameSite("Lax").maxAge(60L * 60 * 24 * 365).build();
        response.addHeader("Set-Cookie", cookie.toString());
        return "redirect:" + WebAdvice.safeLocalPath(back, "/");
    }
}
