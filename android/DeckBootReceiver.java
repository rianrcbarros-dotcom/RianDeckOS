package br.com.rian.deck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class DeckBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent message) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(message.getAction())
            || !context.getSharedPreferences("deck-display", Context.MODE_PRIVATE)
                .getBoolean(KioskPolicy.ARMED, false)) return;
        if (new KioskPolicy(context).owner()) {
            context.startActivity(new Intent(context, DeckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
