package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import software.amazon.awssdk.auth.credentials.internal.WebIdentityCredentialsUtils;

import heidtmare.dmnspwn.config.DmnProperties;
import heidtmare.dmnspwn.edit.Forms.ElementForm;
import heidtmare.dmnspwn.s3.S3Bucket;
import heidtmare.dmnspwn.s3.S3StoreException;
import heidtmare.dmnspwn.s3.S3Sync;
import heidtmare.dmnspwn.store.FileModelStore;
import heidtmare.dmnspwn.store.ModelStore;
import heidtmare.dmnspwn.store.ModelService;

class S3SyncTest {

    @TempDir
    Path dir;

    FakeS3Client s3;
    ModelService models;
    S3Sync sync;

    @BeforeEach
    void setUp() {
        DmnProperties props = TestModels.s3Properties(DmnProperties.Storage.FILE, dir, 10);
        ModelStore store = new FileModelStore(props);
        models = new ModelService(store);
        s3 = new FakeS3Client();
        sync = new S3Sync(new S3Bucket(s3, props), models, store);
        s3.store("dmn/loan.dmn", TestModels.xml("loan-eligibility"));
        s3.store("dmn/team/dish.dmn", TestModels.xml("dish-selection"));
        s3.store("dmn/readme.txt", "hello");
        s3.store("other/secret.dmn", TestModels.xml("dish-selection"));
    }

    /** Without the STS module the default chain skips web identity credentials, which EKS IRSA relies on. */
    @Test
    void webIdentityCredentialsAreAvailable() {
        assertThat(WebIdentityCredentialsUtils.factory()).isNotNull();
    }

    @Test
    void listsFoldersWithinThePrefix() {
        var listing = sync.bucket().list(null, null);
        assertThat(listing.prefix()).isEqualTo("dmn/");
        assertThat(listing.parent()).isNull();
        assertThat(listing.folders()).extracting(f -> f.name()).containsExactly("team");
        assertThat(listing.objects()).extracting(o -> o.name()).containsExactly("loan.dmn", "readme.txt");
        assertThat(sync.bucket().list("dmn/team", null).parent()).isEqualTo("dmn/");
        assertThatThrownBy(() -> sync.bucket().list("other/", null)).isInstanceOf(S3StoreException.class);
    }

    @Test
    void loadsAndLinksModels() {
        String id = sync.load("dmn/loan.dmn");
        assertThat(models.reader(id).info().name()).isEqualTo("Loan Eligibility");
        assertThat(sync.link(id).orElseThrow().key()).isEqualTo("dmn/loan.dmn");
        var status = sync.status(id);
        assertThat(status.localChanged()).isFalse();
        assertThat(status.remote()).isEqualTo(S3Sync.Remote.IN_SYNC);

        // loading again refreshes the same local model
        s3.store("dmn/loan.dmn", TestModels.xml("loan-eligibility").replace("Loan Eligibility", "Loan v2"));
        assertThat(sync.status(id).remote()).isEqualTo(S3Sync.Remote.CHANGED);
        assertThat(sync.load("dmn/loan.dmn")).isEqualTo(id);
        assertThat(models.reader(id).info().name()).isEqualTo("Loan v2");
        assertThat(models.canUndo(id)).isTrue();
    }

    @Test
    void refusesToReloadOverUnpublishedLocalChanges() {
        String id = sync.load("dmn/loan.dmn");
        rename(id, "Locally edited");
        assertThat(sync.status(id).localChanged()).isTrue();
        assertThatThrownBy(() -> sync.load("dmn/loan.dmn")).isInstanceOf(S3StoreException.class)
                .hasMessageContaining("unpublished local changes");
        sync.pull(id);
        assertThat(models.reader(id).element("Risk_Category").orElseThrow().name()).isEqualTo("Risk Category");
    }

    @Test
    void rejectsKeysOutsideThePrefixAndNonDmnObjects() {
        assertThatThrownBy(() -> sync.load("other/secret.dmn")).hasMessageContaining("prefix");
        assertThatThrownBy(() -> sync.load("dmn/../other/secret.dmn")).hasMessageContaining("Invalid");
        assertThatThrownBy(() -> sync.load("dmn/readme.txt")).hasMessageContaining(".dmn");
        assertThatThrownBy(() -> sync.load("dmn/missing.dmn")).hasMessageContaining("does not exist");
    }

    @Test
    void publishesWithOptimisticConcurrency() {
        String id = sync.load("dmn/loan.dmn");
        rename(id, "Edited once");
        assertThat(sync.publish(id, null, false)).isEqualTo("dmn/loan.dmn");
        assertThat(s3.puts.getLast().ifMatch()).isNotNull();
        assertThat(s3.content("dmn/loan.dmn")).contains("Edited once");
        assertThat(sync.status(id).localChanged()).isFalse();

        // someone else changes the object -> publishing must not overwrite it
        s3.store("dmn/loan.dmn", "<changed elsewhere/>");
        rename(id, "Edited twice");
        assertThatThrownBy(() -> sync.publish(id, null, false)).isInstanceOf(S3StoreException.Conflict.class);
        assertThat(s3.content("dmn/loan.dmn")).isEqualTo("<changed elsewhere/>");
        sync.publish(id, null, true);
        assertThat(s3.content("dmn/loan.dmn")).contains("Edited twice");
    }

    @Test
    void publishingToANewKeyNeverOverwritesExistingObjects() {
        String id = models.create("Fresh model");
        assertThat(sync.defaultKey(id)).isEqualTo("dmn/" + id + ".dmn");
        sync.publish(id, null, false);
        assertThat(s3.puts.getLast().ifNoneMatch()).isEqualTo("*");
        assertThat(sync.link(id).orElseThrow().key()).isEqualTo("dmn/" + id + ".dmn");

        assertThatThrownBy(() -> sync.publish(id, "dmn/loan.dmn", false)).isInstanceOf(S3StoreException.Conflict.class)
                .hasMessageContaining("already exists");
        sync.unlink(id);
        assertThat(sync.link(id)).isEmpty();
    }

    @Test
    void enforcesTheSizeLimit() {
        s3.store("dmn/huge.dmn", "x".repeat(70 * 1024));
        assertThatThrownBy(() -> sync.load("dmn/huge.dmn")).hasMessageContaining("limit");
    }

    private void rename(String id, String name) {
        models.update(id, ed -> {
            ElementForm f = new ElementForm();
            f.setName(name);
            ed.updateElement("Risk_Category", f);
            return null;
        });
    }
}
