package br.com.rian.deck;

import android.content.ActivityNotFoundException;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Html;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VerseCard extends LinearLayout {
    static final String SOURCE = "https://biblia.com/bible/bb-sbb-ra/verseoftheday";
    private static final Pattern REFERENCE = Pattern.compile("data-citation=\"([^\"]+)\"");
    private static final Pattern PASSAGE = Pattern.compile("data-passage-text=\"([^\"]+)\"");
    private final TextView title, passage, reference;
    private String source = SOURCE;
    private Verse current;
    private boolean external = true;

    static final class Verse {
        final String reference, passage;
        Verse(String reference, String passage) {
            this.reference = reference;
            this.passage = passage;
        }
    }

    VerseCard(Context context, boolean compact) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(8), dp(12), dp(7));
        setBackground(new VisualAssets.Cover(VisualAssets.bitmap(context, "photos/jesus.jpg"),
            DeckTheme.PANEL, dp(13), true));

        title = label("Versículo do dia", 16, 0xfff6f5f4);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        addView(title);
        passage = label("Carregando versículo…", 14, 0xfff6f5f4);
        passage.setPadding(0, dp(4), dp(5), dp(4));
        passage.setFocusable(true);
        passage.setOnClickListener(view -> showPassage());
        if (compact) {
            passage.setMaxLines(5);
            passage.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(passage, new LayoutParams(-1, 0, 1));
        } else {
            ScrollView viewport = new ScrollView(context);
            viewport.setVerticalScrollBarEnabled(true);
            viewport.setNestedScrollingEnabled(true);
            viewport.addView(passage);
            addView(viewport, new LayoutParams(-1, 0, 1));
        }

        reference = label("Biblia.com ↗", 12, 0xfff38b94);
        reference.setGravity(Gravity.CENTER_VERTICAL);
        reference.setFocusable(true);
        reference.setOnClickListener(view -> {
            if (!external) {
                showPassage();
                return;
            }
            try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(source))); }
            catch (ActivityNotFoundException error) {
                Toast.makeText(context, "Navegador indisponível", Toast.LENGTH_SHORT).show();
            }
        });
        addView(reference, new LayoutParams(-1, dp(29)));
    }

    void allowExternal(boolean allowed) { external = allowed; }

    private void showPassage() {
        if (current != null) new AlertDialog.Builder(getContext()).setTitle(current.reference)
            .setMessage(current.passage).setPositiveButton("Fechar", null).show();
    }

    void artwork(Bitmap image) {
        if (image == null) return;
        title.setContentDescription("Versículo do dia, abrir arte do Biblia.com");
        title.setOnClickListener(view -> {
            ImageView picture = new ImageView(getContext());
            picture.setAdjustViewBounds(true);
            picture.setMaxHeight(dp(420));
            picture.setImageBitmap(image);
            new AlertDialog.Builder(getContext()).setTitle("Arte do dia · Biblia.com")
                .setView(picture).setPositiveButton("Fechar", null).show();
        });
    }

    void update(Verse verse, boolean loading, boolean stale) {
        current = verse;
        passage.setText(verse == null ? (loading ? "Carregando versículo…" : "Versículo indisponível")
            : verse.passage);
        reference.setText(verse == null ? "Biblia.com ↗"
            : verse.reference + (stale ? " · salvo" : "") + " ↗");
        reference.setContentDescription(reference.getText() + (external ? ", abrir no Biblia.com" : ", ler texto completo"));
    }

    void edition(String edition) { source = url(edition); }

    private static String url(String edition) {
        return edition.equals("esv") ? "https://biblia.com/verseoftheday" : SOURCE;
    }

    static Verse fetch(String edition) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url(edition)).openConnection();
        connection.setConnectTimeout(5500);
        connection.setReadTimeout(5500);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "text/html");
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > 1024 * 1024) throw new IllegalStateException("Resposta grande demais");
            }
            String html = new String(output.toByteArray(), StandardCharsets.UTF_8);
            Matcher reference = REFERENCE.matcher(html);
            Matcher passage = PASSAGE.matcher(html);
            if (!reference.find() || !passage.find()) throw new IllegalStateException("Versículo ausente");
            return new Verse(decode(reference.group(1)), decode(passage.group(1)));
        } finally {
            connection.disconnect();
        }
    }

    private static String decode(String value) {
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private int dp(int number) { return Math.round(number * getResources().getDisplayMetrics().density); }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
}
