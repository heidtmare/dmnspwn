package heidtmare.dmnspwn.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.s3.S3ConflictException;
import heidtmare.dmnspwn.s3.S3StoreException;
import heidtmare.dmnspwn.s3.S3Sync;
import heidtmare.dmnspwn.store.ModelService;

/** Browse the configured S3 bucket, load models from it and publish models to it. */
@Controller
@ConditionalOnProperty(prefix = "dmnspwn.s3", name = "enabled", havingValue = "true")
public class S3Controller {

    private final S3Sync sync;
    private final ModelService models;

    public S3Controller(S3Sync sync, ModelService models) {
        this.sync = sync;
        this.models = models;
    }

    @GetMapping("/s3")
    public String browse(@RequestParam(required = false) String prefix, @RequestParam(required = false) String token,
                         Model model) {
        model.addAttribute("bucket", sync.bucket().bucket());
        model.addAttribute("root", sync.bucket().root());
        model.addAttribute("linked", sync.linkedModels());
        try {
            model.addAttribute("listing", sync.bucket().list(prefix, token));
        } catch (S3StoreException e) {
            model.addAttribute("listingError", e.getMessage());
        }
        return "s3";
    }

    @PostMapping("/s3/load")
    public String load(@RequestParam String key, RedirectAttributes flash) {
        String id = sync.load(key);
        flash.addFlashAttribute("success", "Loaded " + sync.bucket().location(key.strip()));
        return "redirect:/models/" + id;
    }

    @GetMapping("/models/{id}/s3")
    public String status(@PathVariable String id, Model model) {
        DmnReader reader = models.reader(id);
        PageSupport.common(model, id, reader, models);
        model.addAttribute("bucket", sync.bucket().bucket());
        model.addAttribute("root", sync.bucket().root());
        model.addAttribute("status", sync.status(id));
        model.addAttribute("defaultKey", sync.defaultKey(id));
        return "model-s3";
    }

    @PostMapping("/models/{id}/s3/publish")
    public String publish(@PathVariable String id, @RequestParam(required = false) String key,
                          @RequestParam(defaultValue = "false") boolean force, RedirectAttributes flash) {
        models.xml(id);
        try {
            String target = sync.publish(id, key, force);
            flash.addFlashAttribute("success", "Published to " + sync.bucket().location(target));
        } catch (S3ConflictException e) {
            flash.addFlashAttribute("error", e.getMessage());
            flash.addFlashAttribute("conflictKey", e.key());
        }
        return "redirect:" + UriComponentsBuilder.fromPath("/models/{id}/s3").buildAndExpand(id).toUriString();
    }

    @PostMapping("/models/{id}/s3/pull")
    public String pull(@PathVariable String id, RedirectAttributes flash) {
        sync.pull(id);
        flash.addFlashAttribute("success", "Replaced the local model with the S3 version (Undo restores it)");
        return "redirect:/models/" + id + "/s3";
    }

    @PostMapping("/models/{id}/s3/unlink")
    public String unlink(@PathVariable String id, RedirectAttributes flash) {
        sync.unlink(id);
        flash.addFlashAttribute("success", "S3 link removed (the S3 object was not changed)");
        return "redirect:/models/" + id + "/s3";
    }
}
