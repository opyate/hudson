package hudson.model;

import com.trilead.ssh2.crypto.Base64;
import hudson.Util;
import hudson.PluginWrapper;
import hudson.node_monitors.ArchitectureMonitor;
import static hudson.util.TimeUnit2.DAYS;
import net.sf.json.JSONObject;
import org.apache.commons.io.output.ByteArrayOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.zip.GZIPOutputStream;
import java.util.List;
import java.util.ArrayList;

/**
 * @author Kohsuke Kawaguchi
 */
public class UsageStatistics extends PageDecorator {
    private final String keyImage;

    /**
     * Lazily computed {@link PublicKey} representation of {@link #keyImage}.
     */
    private volatile transient PublicKey key;

    /**
     * When was the last time we asked a browser to send the usage stats for us?
     */
    private volatile transient long lastAttempt = -1;

    public UsageStatistics() {
        this(DEFAULT_KEY_BYTES);
    }

    /**
     * Creates an instance with a specific public key image.
     */
    public UsageStatistics(String keyImage) {
        super(UsageStatistics.class);
        this.keyImage = keyImage;
        load();
    }

    // register the instance
    static {
        PageDecorator.ALL.add(new UsageStatistics());
    }

    /**
     * No-op, but calling this method makes sure that the static initializer is run.
     */
    static void register() {}

    /**
     * Returns true if it's time for us to check for new version.
     */
    public boolean isDue() {
        // user opted out. no data collection.
        if(!Hudson.getInstance().isUsageStatisticsCollected())     return false;
        
        long now = System.currentTimeMillis();
        if(now - lastAttempt > DAY) {
            lastAttempt = now;
            return true;
        }
        return false;
    }

    private Cipher getCipher() {
        try {
            if (key == null) {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                key = keyFactory.generatePublic(new X509EncodedKeySpec(Util.fromHexString(keyImage)));
            }

            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new Error(e); // impossible
        }
    }

    /**
     * Gets the encrypted usage stat data to be sent to the Hudson server.
     */
    public String getStatData() throws IOException {
        Hudson h = Hudson.getInstance();

        JSONObject o = new JSONObject();
        o.put("stat",1);
        o.put("install", Util.getDigestOf(h.getSecretKey()));
        o.put("version",Hudson.VERSION);

        List<JSONObject> nodes = new ArrayList<JSONObject>();
        for( Computer c : h.getComputers() ) {
            JSONObject  n = new JSONObject();
            if(c.getNode()==h) {
                n.put("master",true);
                n.put("jvm-vendor", System.getProperty("java.vm.vendor"));
                n.put("jvm-version", System.getProperty("java.vm.version"));
            }
            n.put("executors",c.getNumExecutors());
            n.put("os", ArchitectureMonitor.DESCRIPTOR.get(c));
            nodes.add(n);
        }
        o.put("nodes",nodes);

        List<JSONObject> plugins = new ArrayList<JSONObject>();
        for( PluginWrapper pw : h.getPluginManager().getPlugins() ) {
            if(!pw.isActive())  continue;   // treat disabled plugins as if they are uninstalled
            JSONObject p = new JSONObject();
            p.put("name",pw.getShortName());
            p.put("version",pw.getVersion());
            plugins.add(p);
        }
        o.put("plugins",plugins);

        JSONObject jobs = new JSONObject();
        List<TopLevelItem> items = h.getItems();
        for (TopLevelItemDescriptor d : Items.LIST) {
            int cnt=0;
            for (TopLevelItem item : items) {
                if(item.getDescriptor()==d)
                    cnt++;
            }
            jobs.put(d.getJsonSafeClassName(),cnt);
        }
        o.put("jobs",jobs);

        // json -> UTF-8 encode -> gzip -> encrypt -> base64 -> string
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStreamWriter w = new OutputStreamWriter(new GZIPOutputStream(new CipherOutputStream(baos,getCipher())), "UTF-8");
        o.write(w);
        w.close();

        return new String(Base64.encode(baos.toByteArray()));
    }

    /**
     * Public key to encrypt the usage statistics
     */
    private static final String DEFAULT_KEY_BYTES = "30819f300d06092a864886f70d010101050003818d0030818902818100c14970473bd90fd1f2d20e4fa6e36ea21f7d46db2f4104a3a8f2eb097d6e26278dfadf3fe9ed05bbbb00a4433f4b7151e6683a169182e6ff2f6b4f2bb6490b2cddef73148c37a2a7421fc75f99fb0fadab46f191806599a208652f4829fd6f76e13195fb81ff3f2fce15a8e9a85ebe15c07c90b34ebdb416bd119f0d74105f3b0203010001";

    private static final long DAY = DAYS.toMillis(1);
}