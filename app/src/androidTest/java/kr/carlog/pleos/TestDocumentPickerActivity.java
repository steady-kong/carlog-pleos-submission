package kr.carlog.pleos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Test APK only: observe whether the system returns a document picker result. */
public class TestDocumentPickerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "carlog-report-probe.txt");
        startActivityForResult(picker, 1);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1) return;
        Intent response = new Intent(getIntent().getStringExtra("replyAction"));
        response.setPackage(getIntent().getStringExtra("replyPackage"));
        response.putExtra("resultCode", result);
        response.putExtra("hasUri", data != null && data.getData() != null);
        sendBroadcast(response);
        finish();
    }
}
