package kr.carlog.pleos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/** Test APK only; reports an explicitly supplied synthetic geo URI. */
public class TestMapReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent reply = new Intent(getIntent().getStringExtra("replyAction"));
        reply.setPackage(getIntent().getStringExtra("replyPackage"));
        reply.putExtra("uri", getIntent().getDataString());
        reply.putExtra("receiverUid", Process.myUid());
        sendBroadcast(reply);
        finish();
    }
}
