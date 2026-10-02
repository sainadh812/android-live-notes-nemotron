package com.sainadh.livenotes.gemmaprototype;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.google.gson.*;
import com.sainadh.livenotes.gemmaprototype.runtime.ModelStore;
import com.sainadh.livenotes.gemmaprototype.runtime.ModelSpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Deliberately isolated from the production app and its recording database. */
public final class MainActivity extends Activity {
    private static final int TRANSCRIPT = 10, MODEL = 11, SUMMARY_EXPORT = 12, REPORT_EXPORT = 13;
    private static final int MAX_TRANSCRIPT_BYTES = 2 * 1024 * 1024;
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> idleControls = new ArrayList<>();
    private EditText transcript;
    private TextView status, modelStatus, result, sourceStatus;
    private CheckBox reasoning, cpu;
    private Button stop;
    private boolean synthetic, programmaticEdit, draftLoaded;
    private volatile boolean localBusy = true;
    private String displayedSummary = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean busy = SummaryService.isBusy() || localBusy;
            for (View view : idleControls) view.setEnabled(!busy);
            stop.setEnabled(SummaryService.isBusy());
            status.setText(SummaryService.getStatus());
            ModelStore store = new ModelStore(MainActivity.this);
            modelStatus.setText(store.isReady() ? "Gemma 4 E4B · model verified" :
                "Gemma 4 E4B · 3.66 GB download" + (store.partialBytes() > 0 ? " · download can resume" : ""));
            String latest = SummaryService.getSummary();
            if (!latest.isEmpty() && !latest.equals(displayedSummary)) {
                displayedSummary = latest; result.setText(latest);
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(246, 248, 247));
        getWindow().setNavigationBarColor(Color.rgb(246, 248, 247));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.rgb(246, 248, 247));
        int pad = dp(20); content.setPadding(pad,pad,pad,pad);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad, pad + insets.getSystemWindowInsetTop(), pad, pad + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        scroll.addView(content); setContentView(scroll);
        text(content, "GEMMA / LOCAL LAB", 13, Color.rgb(28, 103, 81));
        text(content, "Long meeting summaries", 27, Color.rgb(19, 40, 34));
        text(content, "A separate test app. Your Live Meeting Notes recordings stay in their existing app.", 15, Color.DKGRAY);
        modelStatus = text(content, "Gemma 4 E4B · 3.66 GB download", 18, Color.BLACK);
        text(content, "Download once over Wi-Fi. Summaries run on this device; transcripts are never uploaded. Keep at least 5 GB free.", 14, Color.DKGRAY);
        button(content, "Download / resume model", () -> new AlertDialog.Builder(this)
            .setTitle("Download Gemma 4 E4B")
            .setMessage("Download 3.66 GB from the pinned LiteRT model repository. The model uses Google's Gemma terms. No account or API key is needed.")
            .setNeutralButton("Gemma terms", (d,w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ModelSpec.LICENSE_URL))))
            .setNegativeButton("Cancel", null).setPositiveButton("Download", (d,w) -> launch(SummaryService.DOWNLOAD, null)).show());
        button(content, "Import downloaded .litertlm model", () -> pick(MODEL, "*/*"));
        text(content, "Transcript", 21, Color.BLACK);
        text(content, "Export a recording as text from Live Meeting Notes, then import it here. UTF-8 text, up to 2 MB. The complete text is processed in sections.", 14, Color.DKGRAY);
        button(content, "Import transcript (.txt)", () -> pick(TRANSCRIPT, "text/*"));
        button(content, "Load synthetic 125-minute test", () -> loadFixture());
        sourceStatus = text(content, "Paste or import a transcript", 13, Color.DKGRAY);
        transcript = new EditText(this);
        transcript.setId(View.generateViewId());
        transcript.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TRANSCRIPT_BYTES / 2)});
        transcript.setSaveEnabled(false); // Large transcripts must not enter Activity saved-state Binder transactions.
        transcript.setGravity(Gravity.TOP);
        transcript.setTextSize(14);
        transcript.setHint("Paste transcript here…");
        transcript.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        transcript.setHorizontallyScrolling(false);
        content.addView(transcript, new LinearLayout.LayoutParams(-1, dp(180)));
        idleControls.add(transcript);
        transcript.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                if (!programmaticEdit) { synthetic=false; draftLoaded=true; }
                sourceStatus.setText((synthetic ? "Synthetic test · " : "Your transcript · ") + s.length() + " characters");
            }
            public void afterTextChanged(Editable s) { }
        });
        reasoning = new CheckBox(this); reasoning.setText("Use reasoning for the final summary");
        content.addView(reasoning); idleControls.add(reasoning);
        cpu = new CheckBox(this); cpu.setText("Use CPU (default is GPU)");
        content.addView(cpu); idleControls.add(cpu);
        text(content, "GPU initialization can fall back to CPU. The test report records the actual backend. Reasoning gets a bounded budget; it may not improve accuracy.", 13, Color.DKGRAY);
        button(content, "Summarize / resume", this::summarize);
        stop = new Button(this); stop.setText("Stop and save progress");
        stop.setOnClickListener(v -> startService(new Intent(this, SummaryService.class).setAction(SummaryService.CANCEL)));
        content.addView(stop);
        status = text(content, SummaryService.getStatus(), 15, Color.rgb(28,103,81));
        text(content, "Summary", 21, Color.BLACK);
        text(content, "Source IDs and timestamps let you check claims. A valid citation does not guarantee that the claim is correct; review important decisions.", 13, Color.DKGRAY);
        result = text(content, "Your summary will appear here.", 15, Color.BLACK);
        result.setTextIsSelectable(true);
        button(content, "View cited evidence", this::showEvidence);
        button(content, "Save summary (.md)", () -> export(SUMMARY_EXPORT,"text/markdown","gemma-summary.md"));
        button(content, "Save test report (.json)", () -> export(REPORT_EXPORT,"application/json","gemma-test-report.json"));
        text(content,"The report includes summary evidence and transcript excerpts. It is saved only to a location you choose.",12,Color.DKGRAY);
        reasoning.setChecked(getPreferences(0).getBoolean("thinking",false));
        cpu.setChecked(getPreferences(0).getBoolean("cpu",false));
        io.execute(() -> {
            try {
                String saved = readFile(SummaryService.transcriptFile(this));
                String oldSummary = readFile(SummaryService.summaryFile(this));
                String oldReport = readFile(SummaryService.reportFile(this));
                if (!oldReport.isEmpty()) {
                    JsonObject savedReport = JsonParser.parseString(oldReport).getAsJsonObject();
                    if (savedReport.has("status") && "running".equals(savedReport.get("status").getAsString()))
                        SummaryService.restoreInterruptedStatus();
                }
                runOnUiThread(() -> {
                    setTranscript(saved,getPreferences(0).getBoolean("synthetic",false));
                    if (!oldSummary.isEmpty() && SummaryService.getSummary().isEmpty()) {
                        displayedSummary=oldSummary; result.setText("Previous completed summary\n\n"+oldSummary);
                    }
                });
            } catch (Exception e) { showError(e); } finally { runOnUiThread(() -> localBusy=false); }
        });
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},30);
    }

    private TextView text(LinearLayout parent,String value,int size,int color) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0,dp(9),0,dp(7)); parent.addView(view); return view;
    }
    private void button(LinearLayout parent,String label,Runnable action) {
        Button view=new Button(this); view.setText(label); view.setAllCaps(false); view.setOnClickListener(v->{ if(!SummaryService.isBusy()&&!localBusy) action.run(); });
        parent.addView(view); idleControls.add(view);
    }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
    private void setTranscript(String value,boolean sample) {
        draftLoaded=true; synthetic=sample; programmaticEdit=true; transcript.setText(value); programmaticEdit=false;
    }
    private void pick(int code,String mime) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE),code);
    }
    private void export(int code,String mime,String name) {
        File source=code==SUMMARY_EXPORT?SummaryService.summaryFile(this):SummaryService.reportFile(this);
        if (!source.isFile()) { toast("Complete a test first."); return; }
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE,name),code);
    }
    private void launch(String action,Uri uri) {
        if(SummaryService.isBusy()||localBusy)return;
        Intent intent=new Intent(this,SummaryService.class).setAction(action).setData(uri);
        intent.putExtra("thinking",reasoning.isChecked()).putExtra("cpu",cpu.isChecked()).putExtra("synthetic",synthetic);
        getPreferences(0).edit().putBoolean("thinking",reasoning.isChecked()).putBoolean("cpu",cpu.isChecked())
            .putBoolean("synthetic",synthetic).apply();
        startForegroundService(intent);
    }
    private void summarize() {
        if(SummaryService.isBusy()||localBusy)return;
        String value=transcript.getText().toString();
        if (value.trim().isEmpty()) { toast("Import or paste a transcript first.");return; }
        if (value.getBytes(StandardCharsets.UTF_8).length>MAX_TRANSCRIPT_BYTES) { toast("Transcript exceeds the 2 MB prototype limit.");return; }
        if (!new ModelStore(this).isReady()) { toast("Download or import the Gemma model first.");return; }
        localBusy=true;
        io.execute(()-> {
            try { LocalFiles.write(SummaryService.transcriptFile(this),value);
                runOnUiThread(()-> { localBusy=false; displayedSummary=""; result.setText("Processing this transcript…"); launch(SummaryService.RUN,null); });
            } catch (Exception e) { runOnUiThread(() -> localBusy=false);showError(e); }
        });
    }
    private void loadFixture() {
        if(SummaryService.isBusy()||localBusy)return;
        localBusy=true;
        io.execute(()-> {
            try(InputStream input=getAssets().open("synthetic-125-minute-meeting.txt")) {
                String value=readTranscript(input);
                LocalFiles.write(SummaryService.transcriptFile(this),value);
                getPreferences(0).edit().putBoolean("synthetic",true).apply();
                runOnUiThread(()->setTranscript(value,true));
            } catch(Exception e) {showError(e);} finally {runOnUiThread(() -> localBusy=false);}
        });
    }
    @Override protected void onActivityResult(int request,int resultCode,Intent data) {
        super.onActivityResult(request,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        if(SummaryService.isBusy()||localBusy){toast("Wait for the current operation to finish.");return;}
        Uri uri=data.getData();
        if(request==MODEL) {
            try {getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(SecurityException ignored) { }
            launch(SummaryService.IMPORT,uri); return;
        }
        localBusy=true;
        io.execute(()-> {
            try {
                if(request==TRANSCRIPT) {
                    try(InputStream input=getContentResolver().openInputStream(uri)) {
                        String value=readTranscript(input);
                        LocalFiles.write(SummaryService.transcriptFile(this),value);
                        getPreferences(0).edit().putBoolean("synthetic",false).apply();
                        runOnUiThread(()->setTranscript(value,false));
                    }
                } else {
                    File source=request==SUMMARY_EXPORT?SummaryService.summaryFile(this):SummaryService.reportFile(this);
                    try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")) {
                        if(output==null)throw new IOException("Cannot open destination.");
                        Files.copy(source.toPath(),output);
                    }
                    runOnUiThread(()->toast("Saved."));
                }
            }catch(Exception e){showError(e);}finally{runOnUiThread(() -> localBusy=false);}
        });
    }
    private static String readTranscript(InputStream input)throws IOException {
        if(input==null)throw new IOException("Cannot open transcript.");
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192];int count;
        while((count=input.read(buffer))!=-1){
            if(out.size()+count>MAX_TRANSCRIPT_BYTES)throw new IOException("Transcript exceeds the 2 MB prototype limit.");
            out.write(buffer,0,count);
        }
        String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(out.toByteArray())).toString();
        if(value.length()>MAX_TRANSCRIPT_BYTES/2)throw new IOException("Transcript exceeds the prototype character limit.");
        if(value.indexOf(0)>=0)throw new IOException("Import UTF-8 plain text, not a PDF or audio file.");
        return value.startsWith("\uFEFF")?value.substring(1):value;
    }
    private static String readFile(File file)throws IOException {
        return LocalFiles.read(file);
    }
    private void showEvidence() {
        io.execute(()-> {
            try {
                JsonObject report=JsonParser.parseString(readFile(SummaryService.reportFile(this))).getAsJsonObject();
                if(!report.has("result"))throw new IOException("No completed summary evidence yet.");
                JsonObject data=report.getAsJsonObject("result");Set<String> cited=new LinkedHashSet<>();
                for(JsonElement item:data.getAsJsonArray("items"))for(JsonElement id:item.getAsJsonObject().getAsJsonArray("sources"))cited.add(id.getAsString());
                StringBuilder evidence=new StringBuilder();
                for(JsonElement source:data.getAsJsonArray("sources")){
                    JsonObject s=source.getAsJsonObject();String id=s.get("id").getAsString();
                    if(cited.contains(id))evidence.append(id).append("\n").append(s.get("text").getAsString()).append("\n\n");
                }
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Original cited evidence")
                    .setMessage(evidence.length()==0?"No evidence items.":evidence.toString()).setPositiveButton("Close",null).show());
            }catch(Exception e){showError(e);}
        });
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void showError(Exception e){runOnUiThread(()->toast(e.getMessage()==null?e.toString():e.getMessage()));}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onStop(){
        if(transcript!=null&&draftLoaded&&!SummaryService.isBusy()&&!localBusy){
            String value=transcript.getText().toString();boolean sample=synthetic;
            if(value.getBytes(StandardCharsets.UTF_8).length<=MAX_TRANSCRIPT_BYTES)io.execute(()->{
                try{LocalFiles.write(SummaryService.transcriptFile(this),value);
                    getPreferences(0).edit().putBoolean("synthetic",sample).apply();}catch(IOException ignored){}
            });
        }
        super.onStop();
    }
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);super.onDestroy();}
}
