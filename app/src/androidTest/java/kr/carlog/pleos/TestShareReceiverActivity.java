package kr.carlog.pleos;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Test APK only: read an explicitly shared URI as a separate application UID. */
public class TestShareReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent response = new Intent(getIntent().getStringExtra("replyAction"));
        response.setPackage(getIntent().getStringExtra("replyPackage"));
        response.putExtra("receiverUid", Process.myUid());
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                response.putExtra("text", new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            response.putExtra("error", error.getClass().getSimpleName());
        }
        sendBroadcast(response);
        finish();
    }
}
