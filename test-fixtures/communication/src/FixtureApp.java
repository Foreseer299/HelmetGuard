package com.helmet.guard.fixture;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.ContactsContract;
import android.widget.TextView;

/** Emulator-only fixture. Never install/set as default SMS/dialer on a real user phone. */
public class FixtureApp {
    public static class Screen extends Activity {
        public void onCreate(Bundle state) { super.onCreate(state); TextView v=new TextView(this);v.setText("模拟器通知测试夹具");setContentView(v); }
    }
    public static class Sms extends BroadcastReceiver { public void onReceive(Context c, Intent i) {} }
    public static class Wap extends BroadcastReceiver { public void onReceive(Context c, Intent i) {} }
    public static class Reply extends Service { public IBinder onBind(Intent i) { return null; } }
    public static class Calls extends android.telecom.InCallService {}
    public static class Trigger extends BroadcastReceiver {
        public void onReceive(Context c, Intent i) {
            String kind=i.getStringExtra("kind");
            if ("contact".equals(kind)) { contact(c); return; }
            NotificationManager manager=c.getSystemService(NotificationManager.class);
            int id=i.getIntExtra("id",2300);
            if ("remove".equals(kind)) { manager.cancel(id);return; }
            manager.createNotificationChannel(new NotificationChannel("fixture", "模拟器测试", NotificationManager.IMPORTANCE_HIGH));
            String sender=i.getStringExtra("sender");if(sender==null)sender="";
            String text=i.getStringExtra("text");if(text==null)text="";
            Notification.Builder b=new Notification.Builder(c,"fixture").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(sender).setContentText(text);
            if("call".equals(kind)) b.setCategory(Notification.CATEGORY_CALL).setOngoing(true);
            else {
                b.setCategory(Notification.CATEGORY_MESSAGE);
                if(!"hidden".equals(kind)) {
                    Person.Builder p=new Person.Builder().setName(sender.isEmpty()?"未知":sender);
                    String phone=i.getStringExtra("phone");if(phone!=null)p.setUri("tel:"+phone);
                    Notification.MessagingStyle style = new Notification.MessagingStyle(new Person.Builder().setName("我").build());
                    if("mixed".equals(kind)) style.addMessage("陌生旧消息不能朗读",System.currentTimeMillis()-1000,new Person.Builder().setName("陌生人").build());
                    b.setStyle(style.addMessage(text,System.currentTimeMillis(),p.build()));
                }
            }
            manager.notify(id,b.build());
        }
        private void contact(Context c) {
            try {
                c.getContentResolver().delete(ContactsContract.RawContacts.CONTENT_URI, ContactsContract.RawContacts.ACCOUNT_TYPE+"=?",new String[]{"helmetguard.test"});
                java.util.ArrayList<ContentProviderOperation> ops=new java.util.ArrayList<>();
                ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE,"helmetguard.test").withValue(ContactsContract.RawContacts.ACCOUNT_NAME,"fixture").build());
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID,0).withValue(ContactsContract.Data.MIMETYPE,ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,"测试小明").build());
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID,0).withValue(ContactsContract.Data.MIMETYPE,ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER,"+12025550148").withValue(ContactsContract.CommonDataKinds.Phone.TYPE,ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build());
                c.getContentResolver().applyBatch(ContactsContract.AUTHORITY,ops);
            } catch(Exception e) { throw new RuntimeException("Fixture contact creation failed",e); }
        }
    }
}
