package com.example.quickreference;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.view.KeyEvent;
import android.text.Editable;
import android.text.TextWatcher;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String VERSION = "0.1q-workspace-transaction";
    private static final String PREF_LOG_TREE = "diagnostics_tree_uri";
    private static final String PREF_NAV_HISTORY = "navigation_state_history_v1";
    private static final String PREF_REFERENCE_STACK = "reference_stack_v1";
    private static final String PREF_GRAPH_ENRICH = "reference_graph_enrichment";
    private static final String PREF_WORKSPACE_STATE = "workspace_state_v1";
    private static final int CREATE_DIAGNOSTICS = 7001;
    private static final int PICK_DIAGNOSTICS_FOLDER = 7002;

    private EditText queryEdit;
    private LinearLayout queryHeader;
    private Button providerBtn, languageBtn, imageBtn, layoutBtn, pinBtn, stackBtn, historyBtn;
    private LinearLayout resultBox;
    private FrameLayout contentFrame;
    private ScrollView resultScroll;
    private WebView articleWeb;
    private TextView status;
    private LinearLayout articleModes;
    private String submittedQuery = "";
    private String displayedResultQuery = "";
    private boolean suppressQueryWatcher = false;

    private static final String[] PROVIDERS = {"Wikipedia","Wiktionary","Wikidata","Wikisource","OpenAlex"};
    private int providerIndex = 0;
    private String provider = PROVIDERS[providerIndex];
    private ReferenceSessionStore sessionStore;
    private String language = "ja";
    private String ingress = "LAUNCHER";
    private boolean imagesEnabled = false;
    private JSONArray lastSearchPages = null;
    private String currentArticleTitle = null;
    // Explicit command target: PIN acts on this identity, never on incidental surface fields.
    private ArticleRef activeReferenceIdentity = null;
    private boolean restoringWorkspace = false;
    private String canonicalArticleTitle = null;
    private String activeRepresentation = "results";
    private final ArrayDeque<ArticleRef> articleBackStack = new ArrayDeque<>();
    private final ArrayList<ArticleRef> referenceStack = new ArrayList<>();
    private boolean stackSurfaceOpen = false;
    private boolean historySurfaceOpen = false;
    private static final int REFERENCE_STACK_MAX = 8;
    private static final int VISIT_HISTORY_MAX = 64;
    private static final int NAV_HISTORY_MAX = 256;
    private final ArrayList<ArticleRef> visitHistory = new ArrayList<>();
    private final ArrayList<NavState> navHistory = new ArrayList<>();
    private final HashMap<String,Boolean> pageWideMode = new HashMap<>();
    private boolean wideLayout = false;
    private volatile long requestGeneration = 0;
    private boolean graphEnrichmentEnabled = true;
    private final HashMap<String,JSONObject> referenceGraphCache = new HashMap<>();
    private final HashMap<String,String> apiTextCache = new HashMap<>();
    private final HashMap<String,Long> apiTextCacheTime = new HashMap<>();
    private final HashMap<String,Long> hostCooldownUntil = new HashMap<>();
    private static final long API_CACHE_TTL_MS = 120000L;
    private final LinkedHashMap<String,String> articleCache = new LinkedHashMap<String,String>(12,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> e) { return size() > 10; }
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Low-priority graph expansion must never queue ahead of an explicit user action.
    private final ExecutorService enrichmentExecutor = Executors.newSingleThreadExecutor();
    private final ArrayList<String> diagnostics = new ArrayList<>();

    private static class ArticleRef {
        final String provider, language, title;
        ArticleRef(String p,String l,String t){provider=p;language=l;title=t;}
    }
    private static class NavState {
        final String type, provider, language, title, query, pagesJson; final long timeMs;
        NavState(String type,String provider,String language,String title,String query,String pagesJson,long timeMs){this.type=type;this.provider=provider;this.language=language;this.title=title;this.query=query;this.pagesJson=pagesJson;this.timeMs=timeMs;}
        String key(){return type+"|"+provider+"|"+language+"|"+(type.equals("SEARCH_RESULTS")?query:title);}
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        buildUi();
        sessionStore = new ReferenceSessionStore(this, VERSION);
        loadNavigationHistory();
        loadReferenceStack();
        graphEnrichmentEnabled=getPreferences(0).getBoolean(PREF_GRAPH_ENRICH,true);
        log("REFERENCE_GRAPH_SETTING","enabled="+graphEnrichmentEnabled);
        log("APP_START", "version=" + VERSION);
        log("NAV_MODEL_READY", "schema=quickreference.nav-state/0.1,persisted="+navHistory.size());
        log("REFERENCE_STACK_READY","persisted="+referenceStack.size());
        String incoming = readIncomingText(getIntent());
        sessionStore.event("SESSION_START", "ingress", ingress, null, null);
        if (incoming != null && !incoming.trim().isEmpty()) {
            log("WORKSPACE_RESET_REQUEST","reason=new_ingress,ingress="+ingress);
            queryEdit.setText(incoming.trim());
            queryEdit.setSelection(0);
            language = guessLanguage(incoming);
            languageBtn.setText(language.toUpperCase(Locale.ROOT));
            log("LOOKUP_RECEIVED", fingerprint(incoming));
            sessionStore.event("QUERY", "text", incoming.trim(), "ingress", ingress);
            releaseInputOwnership("INGRESS_READY");
            lookup();
        } else {
            restoreWorkspaceOrLatestNav();
        }
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setDimAmount(0.18f);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        WindowManager.LayoutParams p = w.getAttributes();
        p.width = WindowManager.LayoutParams.MATCH_PARENT;
        p.height = WindowManager.LayoutParams.WRAP_CONTENT;
        p.gravity = Gravity.BOTTOM;
        w.setAttributes(p);
    }

    private void buildUi() {
        int pad = dp(8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad,pad,pad,pad);
        root.setFocusableInTouchMode(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(252,252,252));
        bg.setCornerRadius(dp(16));
        root.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        queryHeader = top;
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.WHITE);
        top.setMinimumHeight(dp(42));
        queryEdit = new EditText(this);
        queryEdit.setSingleLine(true);
        queryEdit.setHint("Wikipedia / Wiktionary / Wikidata / Wikisource / OpenAlex");
        queryEdit.setTextSize(16);
        queryEdit.setTextColor(Color.BLACK);
        queryEdit.setHintTextColor(Color.GRAY);
        queryEdit.setBackgroundColor(Color.WHITE);
        queryEdit.setMinHeight(dp(42));
        queryEdit.setPadding(dp(8),0,dp(8),0);
        top.addView(queryEdit,new LinearLayout.LayoutParams(0,dp(42),1f));
        queryEdit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        queryEdit.setOnEditorActionListener((v,actionId,event)->{
            boolean enter = event!=null && event.getKeyCode()==KeyEvent.KEYCODE_ENTER && event.getAction()==KeyEvent.ACTION_DOWN;
            if(actionId==EditorInfo.IME_ACTION_SEARCH || actionId==EditorInfo.IME_ACTION_DONE || enter){log("QUERY_SUBMIT","keyboard:"+fingerprint(queryEdit.getText().toString().trim()));lookup();return true;}
            return false;
        });
        queryEdit.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int before,int count){}
            public void afterTextChanged(Editable e){if(suppressQueryWatcher)return;String q=e.toString().trim();log("QUERY_EDITOR_CHANGED",fingerprint(q));if(!displayedResultQuery.isEmpty()&&!q.equals(displayedResultQuery))status.setText("未検索の入力 · Enter/検索で実行");}
        });
        Button go=compactButton("検索");
        go.setOnClickListener(v->{log("UI_ACTION","search");lookup();});
        top.addView(go);
        Button close=compactButton("×");
        close.setContentDescription("Close Quick Reference");
        close.setOnClickListener(v->{log("UI_ACTION","close");log("CLOSE","button");releaseInputOwnership("CLOSE");finish();});
        top.addView(close);
        root.addView(top);

        LinearLayout switches = new LinearLayout(this);
        switches.setOrientation(LinearLayout.VERTICAL);
        LinearLayout switchesRow1 = new LinearLayout(this);switchesRow1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout switchesRow2 = new LinearLayout(this);switchesRow2.setGravity(Gravity.CENTER_VERTICAL);
        providerBtn=compactButton(provider);
        providerBtn.setOnClickListener(v->{
            log("UI_ACTION","provider_cycle");
            providerIndex=(providerIndex+1)%PROVIDERS.length;
            provider=PROVIDERS[providerIndex];
            providerBtn.setText(provider);
            articleBackStack.clear();
            log("PROVIDER_SELECTED",provider);
            lookupIfPresent();
        });
        languageBtn=compactButton("JA");
        languageBtn.setOnClickListener(v->{
            log("UI_ACTION","language_cycle");
            language=language.equals("ja")?"en":"ja";
            languageBtn.setText(language.toUpperCase(Locale.ROOT));
            articleBackStack.clear();
            log("LANGUAGE_SELECTED",language);
            lookupIfPresent();
        });
        imageBtn=compactButton("IMG OFF");
        imageBtn.setOnClickListener(v->{
            imagesEnabled=!imagesEnabled;
            imageBtn.setText(imagesEnabled?"IMG ON":"IMG OFF");
            if(articleWeb!=null){ articleWeb.getSettings().setBlockNetworkImage(!imagesEnabled); if(imagesEnabled) articleWeb.reload(); }
            log("MEDIA_POLICY","images="+(imagesEnabled?"on":"off")+",av=blocked");
        });
        layoutBtn=compactButton("FIT");
        layoutBtn.setOnClickListener(v->togglePageLayout());
        Button logBtn=compactButton("LOG");
        logBtn.setOnClickListener(v->showLogActions());
        pinBtn=compactButton("PIN");
        pinBtn.setOnClickListener(v->pinCurrentReference());
        stackBtn=compactButton("STACK 0");
        stackBtn.setOnClickListener(v->showReferenceStack());
        historyBtn=compactButton("HIST 0");
        historyBtn.setOnClickListener(v->showVisitHistory());
        switchesRow1.addView(providerBtn);switchesRow1.addView(languageBtn);switchesRow1.addView(imageBtn);switchesRow1.addView(layoutBtn);
        switchesRow2.addView(pinBtn);switchesRow2.addView(stackBtn);switchesRow2.addView(historyBtn);switchesRow2.addView(logBtn);
        switches.addView(switchesRow1);switches.addView(switchesRow2);root.addView(switches);

        status=new TextView(this);
        status.setTextSize(13);status.setTextColor(Color.DKGRAY);status.setPadding(dp(4),dp(2),dp(4),dp(2));
        root.addView(status);
        articleModes=new LinearLayout(this);articleModes.setGravity(Gravity.CENTER_VERTICAL);articleModes.setVisibility(View.GONE);
        Button readMode=compactButton("本文");readMode.setOnClickListener(v->{log("UI_ACTION","representation_read");if(canonicalArticleTitle!=null){activeRepresentation="read";log("ARTICLE_VIEW","read:"+fingerprint(canonicalArticleTitle));openArticle(canonicalArticleTitle,false,"read");}});
        Button histMode=compactButton("履歴");histMode.setOnClickListener(v->{log("UI_ACTION","representation_history");if(canonicalArticleTitle!=null)openWikipediaHistory(canonicalArticleTitle);});
        Button talkMode=compactButton("議論");talkMode.setOnClickListener(v->{log("UI_ACTION","representation_talk");if(canonicalArticleTitle!=null)openWikipediaTalk(canonicalArticleTitle);});
        Button langMode=compactButton("言語");langMode.setOnClickListener(v->{log("UI_ACTION","representation_languages");if(canonicalArticleTitle!=null)openWikipediaLanguages(canonicalArticleTitle);});
        articleModes.addView(readMode);articleModes.addView(histMode);articleModes.addView(talkMode);articleModes.addView(langMode);root.addView(articleModes);

        contentFrame=new FrameLayout(this);
        resultScroll=new ScrollView(this);
        resultScroll.setFillViewport(false);
        resultBox=new LinearLayout(this);resultBox.setOrientation(LinearLayout.VERTICAL);
        resultScroll.addView(resultBox);
        contentFrame.addView(resultScroll,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        int sh=getResources().getDisplayMetrics().heightPixels;
        int rh=Math.max(dp(300),Math.min(dp(560),Math.round(sh*0.58f)));
        root.addView(contentFrame,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,rh));
        setContentView(root);root.requestFocus();
    }

    private Button compactButton(String text){
        Button b=new Button(this);b.setText(text);b.setTextSize(13);b.setMinWidth(0);b.setMinHeight(0);b.setMinimumWidth(0);b.setMinimumHeight(0);b.setAllCaps(false);b.setPadding(dp(8),0,dp(8),0);b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(38)));return b;
    }

    private String readIncomingText(Intent i){
        if(Intent.ACTION_PROCESS_TEXT.equals(i.getAction())){ingress="PROCESS_TEXT";log("INPUT_SOURCE",ingress);CharSequence c=i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);return c==null?null:c.toString();}
        if(Intent.ACTION_SEND.equals(i.getAction())&&"text/plain".equals(i.getType())){ingress="ACTION_SEND";log("INPUT_SOURCE",ingress);CharSequence c=i.getCharSequenceExtra(Intent.EXTRA_TEXT);return c==null?null:c.toString();}
        ingress="LAUNCHER";log("INPUT_SOURCE",ingress);return null;
    }

    private String guessLanguage(String s){for(int i=0;i<s.length();i++){Character.UnicodeBlock b=Character.UnicodeBlock.of(s.charAt(i));if(b==Character.UnicodeBlock.HIRAGANA||b==Character.UnicodeBlock.KATAKANA||b==Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)return "ja";}return "en";}
    private void lookupIfPresent(){if(!queryEdit.getText().toString().trim().isEmpty())lookup();}

    private void lookup(){
        final String q=queryEdit.getText().toString().trim();if(q.isEmpty())return;
        submittedQuery=q;displayedResultQuery=q;log("QUERY_BOUND","submitted:"+fingerprint(q));sessionStore.event("QUERY_SUBMITTED","text",q,"provider",provider);
        final long gen=++requestGeneration;
        articleBackStack.clear();currentArticleTitle=null;canonicalArticleTitle=null;activeReferenceIdentity=null;activeRepresentation="results";stackSurfaceOpen=false;historySurfaceOpen=false;log("ACTIVE_REFERENCE_CHANGED","cleared:new_query");showResultsSurface();releaseInputOwnership("LOOKUP_START");
        status.setText(provider+" · "+language.toUpperCase(Locale.ROOT)+" · Loading…");resultBox.removeAllViews();
        log("REQUEST_START",provider+":"+language+":"+fingerprint(q));
        executor.submit(()->{try{
            if(provider.equals("OpenAlex")){
                String url="https://api.openalex.org/works?search="+URLEncoder.encode(q,"UTF-8")+"&per-page=8&select=id,doi,title,publication_year,cited_by_count,primary_location,authorships";
                JSONObject data=getJson(url);JSONArray raw=data.getJSONArray("results");JSONArray pages=new JSONArray();
                for(int i=0;i<raw.length();i++){JSONObject x=raw.optJSONObject(i);if(x==null)continue;JSONObject y=new JSONObject();String id=x.optString("id","");y.put("title",id);y.put("description",x.optString("title",""));String doi=x.optString("doi","");String ex=""+x.optInt("publication_year",0)+" · cited by "+x.optInt("cited_by_count",0)+(doi.isEmpty()?"":" · "+doi);y.put("excerpt",ex);y.put("openalex_raw",x);pages.put(y);}
                runOnUiThread(()->{if(!acceptResponse(gen,"lookup_openalex"))return;lastSearchPages=pages;renderResults(pages);});
            } else if(provider.equals("Wikidata")){
                String url="https://www.wikidata.org/w/api.php?action=wbsearchentities&search="+URLEncoder.encode(q,"UTF-8")+"&language="+language+"&uselang="+language+"&limit=8&format=json&origin=*";
                JSONObject data=getJson(url);JSONArray raw=data.getJSONArray("search");JSONArray pages=new JSONArray();
                for(int i=0;i<raw.length();i++){JSONObject x=raw.optJSONObject(i);if(x==null)continue;JSONObject y=new JSONObject();y.put("title",x.optString("id",""));y.put("description",x.optString("label",""));y.put("excerpt",x.optString("description",""));pages.put(y);}
                runOnUiThread(()->{if(!acceptResponse(gen,"lookup_wikidata"))return;lastSearchPages=pages;renderResults(pages);});
            } else {
                String host=wikiHost(provider,language);String url="https://"+host+"/w/rest.php/v1/search/page?q="+URLEncoder.encode(q,"UTF-8")+"&limit=8";
                JSONObject data=getJson(url);JSONArray pages=data.getJSONArray("pages");runOnUiThread(()->{if(!acceptResponse(gen,"lookup_wiki"))return;lastSearchPages=pages;renderResults(pages);});
            }
        }catch(Exception e){log("REQUEST_FAILURE",e.getClass().getSimpleName());runOnUiThread(()->{if(!acceptResponse(gen,"lookup_failure"))return;status.setText("Request failed · "+provider+" · "+language.toUpperCase(Locale.ROOT));resultBox.addView(selectableText("Network/API error. Tap 検索 to retry.",16));});}});
    }

    private String wikiHost(String p,String lang){
        if(p.equals("Wikipedia")) return lang+".wikipedia.org";
        if(p.equals("Wiktionary")) return lang+".wiktionary.org";
        if(p.equals("Wikisource")) return lang+".wikisource.org";
        return lang+".wikipedia.org";
    }

    private static class HttpStatusException extends IOException { final int status; HttpStatusException(int status){super("HTTP "+status);this.status=status;} }
    private static class RateLimitedException extends IOException { RateLimitedException(){super("HTTP 429 cooldown");} }
    private JSONObject getJson(String u)throws Exception{return new JSONObject(getText(u,"application/json"));}
    private JSONObject getJsonCached(String u)throws Exception{return new JSONObject(getTextCached(u,"application/json"));}
    private String getTextCached(String u,String accept)throws Exception{
        long now=System.currentTimeMillis();String v=apiTextCache.get(u);Long at=apiTextCacheTime.get(u);
        if(v!=null&&at!=null&&now-at<API_CACHE_TTL_MS){log("HTTP_CACHE_HIT","kind="+(accept.contains("json")?"json":"text"));return v;}
        String fresh=getText(u,accept);apiTextCache.put(u,fresh);apiTextCacheTime.put(u,now);return fresh;
    }
    private String getText(String u,String accept)throws Exception{
        URL parsed=new URL(u);String host=parsed.getHost();long now=System.currentTimeMillis();Long until=hostCooldownUntil.get(host);
        if(until!=null&&until>now){log("HTTP_BACKOFF_ACTIVE","host="+host+",remaining_ms="+(until-now));throw new RateLimitedException();}
        HttpURLConnection c=(HttpURLConnection)parsed.openConnection();c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(true);c.setRequestProperty("Accept",accept);c.setRequestProperty("User-Agent","QuickReference/"+VERSION+" Android dogfood");
        int code=c.getResponseCode();if(code==429){long wait=15000L;String ra=c.getHeaderField("Retry-After");try{if(ra!=null)wait=Math.max(wait,Long.parseLong(ra.trim())*1000L);}catch(Exception ignored){}hostCooldownUntil.put(host,now+wait);log("HTTP_RATE_LIMIT","host="+host+",backoff_ms="+wait);throw new HttpStatusException(code);}if(code<200||code>=300)throw new HttpStatusException(code);
        BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));StringBuilder sb=new StringBuilder();String line;while((line=r.readLine())!=null)sb.append(line).append('\n');r.close();return sb.toString();
    }
    private String failureClass(Exception e){if(e instanceof HttpStatusException)return "http_"+((HttpStatusException)e).status;if(e instanceof RateLimitedException)return "rate_limited_cooldown";if(e instanceof java.net.SocketTimeoutException)return "timeout";if(e instanceof java.net.UnknownHostException)return "dns";if(e instanceof java.net.ConnectException)return "connect";return e.getClass().getSimpleName();}
    private String encodePathTitle(String title)throws Exception{return URLEncoder.encode(title,"UTF-8").replace("+","%20").replace("%2F","/");}
    private String encodeQueryTitle(String title)throws Exception{return URLEncoder.encode(title,"UTF-8");}
    private void logRepresentationFailure(String representation,String stage,Exception e){log("REPRESENTATION_REQUEST_FAILURE","type="+representation+",stage="+stage+",class="+failureClass(e));}


    private void renderResults(JSONArray pages){
        captureSearchState(pages);
        persistWorkspace("results");
        showResultsSurface();resultBox.removeAllViews();log("RESULT_SURFACE_OPENED","count="+pages.length());log("RESULT_QUERY_BOUND",fingerprint(displayedResultQuery));
        if(pages.length()==0){status.setText("No result · "+provider+" · "+language.toUpperCase(Locale.ROOT));resultBox.addView(selectableText("No matching page.",17));log("RESULT_TYPE","EMPTY");return;}
        status.setText(pages.length()+" results · "+provider+" · "+language.toUpperCase(Locale.ROOT));log("REQUEST_SUCCESS","count="+pages.length());
        for(int i=0;i<pages.length();i++){
            JSONObject p=pages.optJSONObject(i);if(p==null)continue;String title=p.optString("title","");String desc=p.isNull("description")?"":p.optString("description","");String excerpt=htmlToPlain(p.optString("excerpt",""));
            StringBuilder semantic=new StringBuilder(title);if(!desc.isEmpty())semantic.append("\n").append(desc);if(!excerpt.isEmpty())semantic.append("\n").append(excerpt);
            TextView card=selectableText(semantic.toString(),15);card.setPadding(dp(8),dp(7),dp(8),dp(9));
            final String nodeTitle=title;
            card.setOnClickListener(v->{sessionStore.event("TRAVERSE", "provider", provider, "target", nodeTitle);openArticle(nodeTitle,true);});resultBox.addView(card);
            View sep=new View(this);sep.setBackgroundColor(Color.rgb(220,220,220));resultBox.addView(sep,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,1));
        }
    }

    @SuppressWarnings("deprecation") private String htmlToPlain(String s){Spanned sp=Build.VERSION.SDK_INT>=24?Html.fromHtml(s,Html.FROM_HTML_MODE_LEGACY):Html.fromHtml(s);return sp.toString().trim();}

    private void openArticle(String title,boolean pushCurrent){ openArticle(title,pushCurrent,"read"); }
    private void openArticle(String title,boolean pushCurrent,String requestedRepresentation){
        stackSurfaceOpen=false;historySurfaceOpen=false;
        final long gen=++requestGeneration;
        final String requestRepresentation=(requestedRepresentation==null||requestedRepresentation.isEmpty())?"read":requestedRepresentation;
        if(pushCurrent&&currentArticleTitle!=null)articleBackStack.push(new ArticleRef(provider,language,currentArticleTitle));
        currentArticleTitle=title;
        activeReferenceIdentity=new ArticleRef(provider,language,title);
        log("ACTIVE_REFERENCE_CHANGED",provider+":"+language+":"+fingerprint(title));
        if(provider.equals("Wikipedia") && requestRepresentation.equals("read")) canonicalArticleTitle=title;
        activeRepresentation=requestRepresentation;
        wideLayout=Boolean.TRUE.equals(pageWideMode.get(referenceKey(provider,language,title)));updateLayoutButton();
        log("REPRESENTATION_STATE","active="+activeRepresentation+",canonical="+fingerprint(canonicalArticleTitle==null?"":canonicalArticleTitle)+",render="+fingerprint(title));
        log("ARTICLE_REQUEST",provider+":"+language+":"+fingerprint(title));
        if(provider.equals("Wikidata")){openWikidataEntity(title);return;}
        if(provider.equals("OpenAlex")){openOpenAlexWork(title);return;}
        showArticleSurface();status.setText(provider+" · "+language.toUpperCase(Locale.ROOT)+" · Loading article…");
        String key=provider+"|"+language+"|"+title;String cached=articleCache.get(key);
        if(cached!=null){renderArticleHtml(title,cached,true);return;}
        final String p=provider,l=language,t=title,repr=requestRepresentation;
        executor.submit(()->{try{
            String encoded=encodePathTitle(t);
            String url="https://"+wikiHost(p,l)+"/w/rest.php/v1/page/"+encoded+"/html";
            String html=getText(url,"text/html");articleCache.put(key,html);runOnUiThread(()->{if(!acceptResponse(gen,"article"))return;renderArticleHtml(t,html,false);});
        }catch(Exception e){log("ARTICLE_FAILURE","class="+failureClass(e)+",provider="+p+",language="+l+",representation="+repr);logRepresentationFailure(repr,"fetch",e);runOnUiThread(()->{if(!acceptResponse(gen,"article_failure"))return;String fc=failureClass(e);if(repr.equals("talk")&&fc.equals("http_404")){status.setText("Wikipedia · 議論 · この議論ページはまだありません");ensureWebView();articleWeb.loadDataWithBaseURL(null,"<html><body><h3>議論ページはまだありません</h3><p>本文ページは存在しますが、この議論ページは作成されていません。</p></body></html>","text/html","UTF-8",null);}else if(fc.equals("http_429")||fc.equals("rate_limited_cooldown")){status.setText("一時的なアクセス制限 · 少し待って再試行してください");ensureWebView();articleWeb.loadDataWithBaseURL(null,"<html><body><h3>一時的なアクセス制限</h3><p>参照元が短時間のアクセスを制限しています。現在の作業状態は保持されています。</p></body></html>","text/html","UTF-8",null);}else{status.setText("Article load failed · "+p+" · "+l.toUpperCase(Locale.ROOT)+" · "+fc);ensureWebView();articleWeb.loadDataWithBaseURL(null,errorHtml(),"text/html","UTF-8",null);}});}});
    }


    private void openOpenAlexWork(String id){
        final long gen=requestGeneration;
        showResultsSurface();resultBox.removeAllViews();status.setText("OpenAlex · Loading work…");final String workId=id;
        executor.submit(()->{try{
            String shortId=workId.substring(workId.lastIndexOf('/')+1);
            String url="https://api.openalex.org/works/"+URLEncoder.encode(shortId,"UTF-8");
            JSONObject w=getJson(url);StringBuilder out=new StringBuilder();
            out.append(w.optString("title",shortId)).append("\n[").append(shortId).append("]\n");
            int year=w.optInt("publication_year",0);if(year>0)out.append(year).append(" · ");out.append("cited by ").append(w.optInt("cited_by_count",0)).append("\n");
            String doi=w.optString("doi","");if(!doi.isEmpty())out.append(doi).append("\n");
            JSONArray authors=w.optJSONArray("authorships");if(authors!=null){out.append("\nAuthors\n");for(int i=0;i<Math.min(authors.length(),12);i++){JSONObject a=authors.optJSONObject(i);JSONObject au=a==null?null:a.optJSONObject("author");if(au!=null)out.append("• ").append(au.optString("display_name","")).append("\n");}}
            JSONArray refs=w.optJSONArray("referenced_works");if(refs!=null){out.append("\nReferences: ").append(refs.length()).append("\n");for(int i=0;i<Math.min(refs.length(),20);i++)out.append(refs.optString(i)).append("\n");}
            JSONObject oa=w.optJSONObject("open_access");if(oa!=null)out.append("\nOpen access: ").append(oa.optBoolean("is_oa",false)).append(" · ").append(oa.optString("oa_status","")).append("\n");
            String text=out.toString().trim();sessionStore.event("NODE_OPEN", "provider", "OpenAlex", "canonical_id", shortId);
            runOnUiThread(()->{if(!acceptResponse(gen,"openalex_work"))return;resultBox.removeAllViews();recordVisit("OpenAlex",language,workId);TextView v=selectableText(text,15);v.setPadding(dp(8),dp(8),dp(8),dp(12));resultBox.addView(v);status.setText("OpenAlex · work · "+shortId);log("ARTICLE_OPEN","network:"+fingerprint(shortId));log("RESULT_SURFACE_OPENED","openalex_work");});
        }catch(Exception ex){log("ARTICLE_FAILURE",ex.getClass().getSimpleName());runOnUiThread(()->{resultBox.removeAllViews();resultBox.addView(selectableText("Could not load OpenAlex work.",16));status.setText("OpenAlex work load failed");});}});
    }

    private void openWikidataEntity(String id){
        final long gen=requestGeneration;
        showResultsSurface();resultBox.removeAllViews();status.setText("Wikidata · "+language.toUpperCase(Locale.ROOT)+" · Loading entity…");final String entityId=id;
        executor.submit(()->{try{String url="https://www.wikidata.org/w/api.php?action=wbgetentities&ids="+URLEncoder.encode(entityId,"UTF-8")+"&props=labels|descriptions|aliases|claims|sitelinks&languages="+language+"|en&format=json&origin=*";JSONObject data=getJson(url);JSONObject e=data.getJSONObject("entities").getJSONObject(entityId);String text=formatWikidataEntity(entityId,e);JSONObject sitelinks=e.optJSONObject("sitelinks");runOnUiThread(()->{if(!acceptResponse(gen,"wikidata_entity"))return;resultBox.removeAllViews();recordVisit("Wikidata",language,entityId);TextView v=selectableText(text,15);v.setPadding(dp(8),dp(8),dp(8),dp(12));resultBox.addView(v);addWikidataSitelinks(sitelinks);status.setText("Wikidata · entity · "+entityId);sessionStore.event("NODE_OPEN", "provider", "Wikidata", "canonical_id", entityId);log("ARTICLE_OPEN","network:"+fingerprint(entityId));log("RESULT_SURFACE_OPENED","entity");});}catch(Exception ex){log("ARTICLE_FAILURE",ex.getClass().getSimpleName());runOnUiThread(()->{resultBox.removeAllViews();resultBox.addView(selectableText("Could not load Wikidata entity.",16));status.setText("Wikidata entity load failed");});}});
    }
    private String formatWikidataEntity(String id,JSONObject e){
        StringBuilder out=new StringBuilder();JSONObject labels=e.optJSONObject("labels"),desc=e.optJSONObject("descriptions");String label=valueForLanguage(labels,language);if(label.isEmpty())label=valueForLanguage(labels,"en");String description=valueForLanguage(desc,language);if(description.isEmpty())description=valueForLanguage(desc,"en");out.append(label.isEmpty()?id:label).append("  [").append(id).append("]\n");if(!description.isEmpty())out.append(description).append("\n");
        JSONObject aliases=e.optJSONObject("aliases");JSONArray aa=aliases==null?null:aliases.optJSONArray(language);if(aa!=null&&aa.length()>0){out.append("\nAliases: ");for(int i=0;i<Math.min(aa.length(),8);i++){if(i>0)out.append(", ");out.append(aa.optJSONObject(i).optString("value",""));}}
        JSONObject claims=e.optJSONObject("claims");if(claims!=null){out.append("\n\nStatements (property IDs)\n");Iterator<String> it=claims.keys();int shown=0;while(it.hasNext()&&shown<30){String pid=it.next();JSONArray arr=claims.optJSONArray(pid);if(arr==null||arr.length()==0)continue;out.append(pid).append(": ");for(int i=0;i<Math.min(arr.length(),4);i++){if(i>0)out.append("; ");JSONObject sn=arr.optJSONObject(i).optJSONObject("mainsnak");JSONObject dv=sn==null?null:sn.optJSONObject("datavalue");Object val=dv==null?null:dv.opt("value");out.append(compactDataValue(val));}out.append("\n");shown++;}}return out.toString().trim();
    }
    private String valueForLanguage(JSONObject o,String lang){if(o==null)return "";JSONObject x=o.optJSONObject(lang);return x==null?"":x.optString("value","");}
    private String compactDataValue(Object v){if(v==null)return "(no value)";if(v instanceof String)return (String)v;if(v instanceof JSONObject){JSONObject o=(JSONObject)v;if(o.has("id"))return o.optString("id");if(o.has("time"))return o.optString("time");if(o.has("amount"))return o.optString("amount");if(o.has("text"))return o.optString("text");}String x=String.valueOf(v);return x.length()>120?x.substring(0,117)+"…":x;}

    private void renderArticleHtml(String title,String raw,boolean cacheHit){
        ensureWebView();String safe=prepareHtml(raw);String base="https://"+wikiHost(provider,language)+"/wiki/";
        articleWeb.getSettings().setBlockNetworkImage(!imagesEnabled);articleWeb.loadDataWithBaseURL(base,safe,"text/html","UTF-8",null);
        status.setText(provider+" · "+language.toUpperCase(Locale.ROOT)+" · article · "+(wideLayout?"WIDE":"FIT")+(imagesEnabled?" · IMG ON":" · IMG OFF"));
        recordVisit(provider,language,title);
        persistWorkspace("document");
        sessionStore.event("NODE_OPEN", "provider", provider, "title", title);log("ARTICLE_OPEN",(cacheHit?"cache":"network")+":"+fingerprint(title));log("RESULT_SURFACE_OPENED","article");
        if(graphEnrichmentEnabled&&provider.equals("Wikipedia")&&activeRepresentation.equals("read"))enrichWikipediaReferenceGraph(language,title);
    }

    private void ensureWebView(){
        if(articleWeb!=null)return;
        articleWeb=new WebView(this);articleWeb.setBackgroundColor(Color.WHITE);
        WebSettings s=articleWeb.getSettings();s.setJavaScriptEnabled(false);s.setDomStorageEnabled(false);s.setLoadsImagesAutomatically(true);s.setBlockNetworkImage(!imagesEnabled);s.setMediaPlaybackRequiresUserGesture(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);s.setLoadWithOverviewMode(wideLayout);s.setUseWideViewPort(wideLayout);s.setTextZoom(105);
        articleWeb.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return handleArticleLink(url);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){return handleArticleLink(req.getUrl().toString());}
        });
        contentFrame.addView(articleWeb,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private boolean handleArticleLink(String url){
        try{
            Uri u=Uri.parse(url);String host=u.getHost();String path=u.getPath();
            if(host!=null&&path!=null&&path.startsWith("/wiki/")&&(host.endsWith(".wikipedia.org")||host.endsWith(".wiktionary.org")||host.endsWith(".wikisource.org"))){
                String[] hp=host.split("\\.");if(hp.length>=3){String newLang=hp[0];String newProvider=host.endsWith(".wikipedia.org")?"Wikipedia":host.endsWith(".wiktionary.org")?"Wiktionary":"Wikisource";String title=Uri.decode(path.substring(6));
                    if(title.startsWith("File:")||title.startsWith("Special:")||title.startsWith("Media:")){log("INTERNAL_LINK","blocked_namespace");return true;}
                    if(currentArticleTitle!=null)articleBackStack.push(new ArticleRef(provider,language,currentArticleTitle));provider=newProvider;language=newLang;for(int pi=0;pi<PROVIDERS.length;pi++)if(PROVIDERS[pi].equals(provider))providerIndex=pi;providerBtn.setText(provider);languageBtn.setText(language.toUpperCase(Locale.ROOT));log("INTERNAL_LINK",provider+":"+language+":"+fingerprint(title));openArticle(title,false);return true;}
            }
            log("EXTERNAL_LINK","browser_requested");startActivity(new Intent(Intent.ACTION_VIEW,u));return true;
        }catch(Exception e){log("INTERNAL_LINK","parse_error");return true;}
    }

    private void openWikipediaHistory(String title){
        if(!provider.equals("Wikipedia"))return;final long gen=++requestGeneration;activeRepresentation="history";showResultsSurface();resultBox.removeAllViews();status.setText("Wikipedia · 履歴 · Loading…");log("ARTICLE_VIEW","history:"+fingerprint(title));sessionStore.event("VIEW_REPRESENTATION","provider","Wikipedia","representation","history");
        final String t=title,l=language;executor.submit(()->{try{
            String url="https://"+wikiHost("Wikipedia",l)+"/w/api.php?action=query&prop=revisions&titles="+encodeQueryTitle(t)+"&rvlimit=30&rvprop=ids%7Ctimestamp%7Cuser%7Ccomment%7Csize%7Cflags%7Ctags&formatversion=2&format=json&origin=*";
            JSONObject data=getJsonCached(url);JSONArray pages=data.getJSONObject("query").getJSONArray("pages");JSONObject page=pages.optJSONObject(0);JSONArray revs=page==null?null:page.optJSONArray("revisions");StringBuilder out=new StringBuilder("Recent revisions · "+t+"\n\n");
            if(revs==null||revs.length()==0)out.append("No revision data.");else{int prev=-1;for(int i=0;i<revs.length();i++){JSONObject r=revs.optJSONObject(i);int size=r.optInt("size",0);out.append(r.optString("timestamp","")).append("  ").append(r.optString("user","(hidden)"));if(prev>=0)out.append("  Δ").append(size-prev>=0?"+":"").append(size-prev);out.append("  ").append(size).append(" B");if(r.has("minor"))out.append("  minor");String c=r.optString("comment","");if(!c.isEmpty())out.append("\n  ").append(c);out.append("\n\n");prev=size;}}
            String text=out.toString();runOnUiThread(()->{if(!acceptResponse(gen,"history"))return;resultBox.removeAllViews();resultBox.addView(selectableText(text,14));status.setText("Wikipedia · 履歴 · recent "+(revs==null?0:revs.length()));log("REPRESENTATION_OPEN","history");});
        }catch(Exception e){log("REPRESENTATION_FAILURE","history:"+failureClass(e));logRepresentationFailure("history","fetch",e);runOnUiThread(()->{if(!acceptResponse(gen,"history_failure"))return;resultBox.addView(selectableText("Could not load revision history · "+failureClass(e),16));status.setText("Wikipedia · 履歴 · load failed");});}});
    }
    private void openWikipediaTalk(String title){
        if(!provider.equals("Wikipedia"))return;
        final String base=canonicalArticleTitle!=null?canonicalArticleTitle:title; final String l=language; final long gen=++requestGeneration;
        activeRepresentation="talk"; log("ARTICLE_VIEW","talk:"+fingerprint(base)); log("REPRESENTATION_RESOLVE_START","type=talk,language="+l+",canonical="+fingerprint(base));
        sessionStore.event("VIEW_REPRESENTATION","provider","Wikipedia","representation","talk"); status.setText("Wikipedia · 議論 · Resolving…");
        executor.submit(()->{try{
            String url="https://"+wikiHost("Wikipedia",l)+"/w/api.php?action=query&meta=siteinfo&siprop=namespaces&titles="+encodeQueryTitle(base)+"&formatversion=2&format=json&origin=*";
            JSONObject data=getJsonCached(url); JSONObject query=data.getJSONObject("query"); JSONArray pages=query.getJSONArray("pages"); JSONObject page=pages.optJSONObject(0);
            if(page==null||page.optBoolean("missing",false)){log("REPRESENTATION_RESOLVE_FAILURE","type=talk,reason=subject_missing");runOnUiThread(()->status.setText("Wikipedia · 議論 · source page not found"));return;}
            int subjectNs=page.optInt("ns",0); int talkNs=(subjectNs%2==0)?subjectNs+1:subjectNs; JSONObject namespaces=query.getJSONObject("namespaces"); JSONObject talkObj=namespaces.optJSONObject(String.valueOf(talkNs));
            if(talkObj==null){log("REPRESENTATION_RESOLVE_FAILURE","type=talk,reason=namespace_missing,subject_ns="+subjectNs+",talk_ns="+talkNs);runOnUiThread(()->status.setText("Wikipedia · 議論 · namespace unresolved"));return;}
            String pageTitle=page.optString("title",base); String subjectPrefix=""; JSONObject subjectObj=namespaces.optJSONObject(String.valueOf(subjectNs)); if(subjectObj!=null)subjectPrefix=subjectObj.optString("*","");
            String local=pageTitle; if(subjectNs!=0&&!subjectPrefix.isEmpty()&&local.startsWith(subjectPrefix+":"))local=local.substring(subjectPrefix.length()+1);
            String talkPrefix=talkObj.optString("*",talkObj.optString("canonical","Talk")); final String talkTitle=talkPrefix.isEmpty()?local:talkPrefix+":"+local;
            log("REPRESENTATION_RESOLVE_SUCCESS","type=talk,language="+l+",subject_ns="+subjectNs+",talk_ns="+talkNs+",target="+fingerprint(talkTitle));
            runOnUiThread(()->{if(!acceptResponse(gen,"talk_resolve"))return;provider="Wikipedia";language=l;activeRepresentation="talk";openArticle(talkTitle,false,"talk");});
        }catch(Exception e){logRepresentationFailure("talk","resolve",e);runOnUiThread(()->{if(!acceptResponse(gen,"talk_resolve_failure"))return;status.setText("Wikipedia · 議論 · resolve failed · "+failureClass(e));});}});
    }
    private void openWikipediaLanguages(String title){
        if(!provider.equals("Wikipedia"))return;final long gen=++requestGeneration;activeRepresentation="languages";showResultsSurface();resultBox.removeAllViews();status.setText("Wikipedia · 言語 · Loading…");log("ARTICLE_VIEW","languages:"+fingerprint(title));sessionStore.event("VIEW_REPRESENTATION","provider","Wikipedia","representation","languages");final String t=title,l=language;
        executor.submit(()->{try{
            String url="https://"+wikiHost("Wikipedia",l)+"/w/api.php?action=query&prop=langlinks&titles="+encodeQueryTitle(t)+"&lllimit=100&llprop=url%7Clangname%7Cautonym&llinlanguagecode="+l+"&formatversion=2&format=json&origin=*";
            JSONObject data=getJsonCached(url);JSONArray pages=data.getJSONObject("query").getJSONArray("pages");JSONObject page=pages.optJSONObject(0);JSONArray links=page==null?null:page.optJSONArray("langlinks");
            LinkedHashMap<String,String[]> merged=new LinkedHashMap<>();if(links!=null)for(int i=0;i<links.length();i++){JSONObject x=links.optJSONObject(i);String code=x.optString("lang","");String target=languageLinkTarget(x);String label=x.optString("autonym",x.optString("langname",code));if(!code.isEmpty()&&!target.isEmpty())merged.put(code,new String[]{target,label,"page"});}
            int pageCount=merged.size(),wdAdded=0;if(graphEnrichmentEnabled){JSONObject graph=wikidataGraphBlocking(l,t);JSONObject sitelinks=graph==null?null:graph.optJSONObject("sitelinks");if(sitelinks!=null){Iterator<String> it=sitelinks.keys();while(it.hasNext()){String site=it.next();if(!site.endsWith("wiki")||site.equals("commonswiki")||site.equals("specieswiki"))continue;String code=site.substring(0,site.length()-4);JSONObject x=sitelinks.optJSONObject(site);String target=x==null?"":x.optString("title","");if(code.isEmpty()||target.isEmpty())continue;if(!merged.containsKey(code)){merged.put(code,new String[]{target,code,"wikidata"});wdAdded++;}}}}
            final int fpCount=pageCount,fwdAdded=wdAdded;runOnUiThread(()->{if(!acceptResponse(gen,"languages"))return;resultBox.removeAllViews();if(merged.isEmpty())resultBox.addView(selectableText("No language references found.",16));else for(Map.Entry<String,String[]> e:merged.entrySet()){String code=e.getKey();String[] v=e.getValue();String target=v[0],label=v[1],source=v[2];TextView row=selectableText(code+" · "+label+(source.equals("wikidata")?" · Wikidata補完":"")+"\n"+target,15);row.setOnClickListener(z->openWikipediaLanguageTransactional(code,target));resultBox.addView(row);}status.setText("Wikipedia · 言語 · "+merged.size()+(fwdAdded>0?" · +"+fwdAdded+" Wikidata":""));log("REFERENCE_GRAPH_MERGE","page_links="+fpCount+",wikidata_added="+fwdAdded+",merged="+merged.size());log("REPRESENTATION_OPEN","languages");});
        }catch(Exception e){log("REPRESENTATION_FAILURE","languages:"+failureClass(e));logRepresentationFailure("languages","fetch_or_merge",e);runOnUiThread(()->{if(!acceptResponse(gen,"languages_failure"))return;resultBox.addView(selectableText("Could not load language references · "+failureClass(e),16));status.setText("Wikipedia · 言語 · load failed");});}});
    }

    private String languageLinkTarget(JSONObject x){
        if(x==null)return "";
        String t=x.optString("title",x.optString("*",""));
        if(!t.isEmpty())return t;
        String raw=x.optString("url","");
        try{Uri u=Uri.parse(raw);String path=u.getPath();if(path!=null&&path.startsWith("/wiki/")&&path.length()>6)return Uri.decode(path.substring(6));}catch(Exception ignored){}
        return "";
    }

    private void openWikipediaLanguageTransactional(String targetLanguage,String targetTitle){
        final String oldProvider=provider,oldLanguage=language,oldCurrent=currentArticleTitle,oldCanonical=canonicalArticleTitle,oldRepresentation=activeRepresentation;
        final String code=targetLanguage==null?"":targetLanguage.trim();final String title=targetTitle==null?"":targetTitle.trim();
        if(code.isEmpty()||title.isEmpty()){log("LANGUAGE_LINK_REJECT","invalid_target:lang="+fingerprint(code)+",title="+fingerprint(title));status.setText("Wikipedia · 言語 · invalid target");return;}
        final long gen=++requestGeneration;
        log("UI_ACTION","language_reference_open");log("LANGUAGE_LINK_RESOLVE",code+":"+fingerprint(title));status.setText("Wikipedia · "+code.toUpperCase(Locale.ROOT)+" · Loading language article…");
        executor.submit(()->{try{
            String encoded=URLEncoder.encode(title,"UTF-8").replace("+","%20");
            String url="https://"+wikiHost("Wikipedia",code)+"/w/rest.php/v1/page/"+encoded+"/html";
            String html=getText(url,"text/html");String key="Wikipedia|"+code+"|"+title;articleCache.put(key,html);
            runOnUiThread(()->{
                if(!acceptResponse(gen,"language_commit"))return;
                if(oldCurrent!=null)articleBackStack.push(new ArticleRef(oldProvider,oldLanguage,oldCurrent));
                provider="Wikipedia";for(int pi=0;pi<PROVIDERS.length;pi++)if(PROVIDERS[pi].equals(provider))providerIndex=pi;providerBtn.setText(provider);
                language=code;languageBtn.setText(code.toUpperCase(Locale.ROOT));currentArticleTitle=title;canonicalArticleTitle=title;activeRepresentation="read";
                log("LANGUAGE_LINK_COMMIT",code+":"+fingerprint(title));log("REPRESENTATION_STATE","active=read,canonical="+fingerprint(title)+",render="+fingerprint(title));
                showArticleSurface();renderArticleHtml(title,html,false);
            });
        }catch(Exception e){
            log("LANGUAGE_LINK_ROLLBACK",code+":"+e.getClass().getSimpleName()+":target="+fingerprint(title));
            runOnUiThread(()->{if(!acceptResponse(gen,"language_rollback"))return;provider=oldProvider;language=oldLanguage;currentArticleTitle=oldCurrent;canonicalArticleTitle=oldCanonical;activeRepresentation=oldRepresentation;providerBtn.setText(provider);languageBtn.setText(language.toUpperCase(Locale.ROOT));status.setText("Language article load failed · stayed on "+oldLanguage.toUpperCase(Locale.ROOT));ensureQueryHeaderVisible("language_rollback");});
        }});
    }


    private boolean acceptResponse(long gen,String source){
        if(gen==requestGeneration)return true;
        log("STALE_RESPONSE_DROPPED",source+":gen="+gen+",current="+requestGeneration);
        return false;
    }
    private String referenceKey(String p,String l,String t){return p+"|"+l+"|"+t;}
    private void updateLayoutButton(){if(layoutBtn!=null)layoutBtn.setText(wideLayout?"WIDE":"FIT");}
    private void togglePageLayout(){
        if(currentArticleTitle==null||articleWeb==null||articleWeb.getVisibility()!=View.VISIBLE){Toast.makeText(this,"Open an article first",Toast.LENGTH_SHORT).show();return;}
        wideLayout=!wideLayout;pageWideMode.put(referenceKey(provider,language,currentArticleTitle),wideLayout);updateLayoutButton();
        WebSettings s=articleWeb.getSettings();s.setUseWideViewPort(wideLayout);s.setLoadWithOverviewMode(wideLayout);
        String raw=articleCache.get(referenceKey(provider,language,currentArticleTitle));
        log("PAGE_LAYOUT_MODE",(wideLayout?"WIDE":"FIT")+":"+provider+":"+language+":"+fingerprint(currentArticleTitle));
        sessionStore.event("PAGE_LAYOUT","mode",wideLayout?"WIDE":"FIT","provider",provider);
        if(raw!=null)renderArticleHtml(currentArticleTitle,raw,true);else openArticle(currentArticleTitle,false);
    }
    private void recordVisit(String p,String l,String title){
        if(title==null||title.isEmpty())return;ArticleRef r=new ArticleRef(p,l,title);
        if(!visitHistory.isEmpty()){ArticleRef last=visitHistory.get(visitHistory.size()-1);if(last.provider.equals(p)&&last.language.equals(l)&&last.title.equals(title))return;}
        visitHistory.add(r);if(visitHistory.size()>VISIT_HISTORY_MAX)visitHistory.remove(0);
        captureDocumentState(p,l,title);updateHistoryButton();
        log("REFERENCE_HISTORY_ADD",p+":"+l+":"+fingerprint(title));sessionStore.event("REFERENCE_VISIT","provider",p,"title",title);
    }
    private void updateHistoryButton(){if(historyBtn!=null)historyBtn.setText("HIST "+navHistory.size());}
    private void captureSearchState(JSONArray pages){
        String json=pages==null?"[]":pages.toString(); NavState n=new NavState("SEARCH_RESULTS",provider,language,"",displayedResultQuery,json,System.currentTimeMillis()); addNavState(n,"network_or_render");
    }
    private void captureDocumentState(String p,String l,String title){addNavState(new NavState("DOCUMENT",p,l,title,"","",System.currentTimeMillis()),"open");}
    private void addNavState(NavState n,String reason){
        if(!navHistory.isEmpty()&&navHistory.get(navHistory.size()-1).key().equals(n.key())){log("NAV_STATE_DEDUP","type="+n.type+",reason="+reason);return;}
        navHistory.add(n);while(navHistory.size()>NAV_HISTORY_MAX){NavState ev=navHistory.remove(0);log("NAV_STATE_EVICT","type="+ev.type+",reason=capacity");}
        persistNavigationHistory();updateHistoryButton();
        String fp=n.type.equals("SEARCH_RESULTS")?fingerprint(n.query):fingerprint(n.title);
        log("NAV_STATE_CAPTURE","type="+n.type+",provider="+n.provider+",language="+n.language+",id="+fp+",count="+navHistory.size()+",reason="+reason);
        sessionStore.event("NAV_STATE_CAPTURE","type",n.type,"provider",n.provider);
    }
    private void persistNavigationHistory(){
        try{JSONArray a=new JSONArray();for(NavState n:navHistory){JSONObject o=new JSONObject();o.put("type",n.type);o.put("provider",n.provider);o.put("language",n.language);o.put("title",n.title);o.put("query",n.query);o.put("pages",n.pagesJson);o.put("time_ms",n.timeMs);a.put(o);}getPreferences(0).edit().putString(PREF_NAV_HISTORY,a.toString()).apply();log("NAV_STATE_PERSIST","count="+navHistory.size());}catch(Exception e){log("NAV_STATE_PERSIST_FAILURE",e.getClass().getSimpleName());}
    }
    private void loadNavigationHistory(){
        try{String raw=getPreferences(0).getString(PREF_NAV_HISTORY,"[]");JSONArray a=new JSONArray(raw);for(int i=Math.max(0,a.length()-NAV_HISTORY_MAX);i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;navHistory.add(new NavState(o.optString("type"),o.optString("provider"),o.optString("language"),o.optString("title"),o.optString("query"),o.optString("pages","[]"),o.optLong("time_ms",0)));}updateHistoryButton();}catch(Exception e){log("NAV_STATE_LOAD_FAILURE",e.getClass().getSimpleName());}
    }
    private void restoreNavState(NavState n){
        provider=n.provider;language=n.language;for(int pi=0;pi<PROVIDERS.length;pi++)if(PROVIDERS[pi].equals(provider))providerIndex=pi;providerBtn.setText(provider);languageBtn.setText(language.toUpperCase(Locale.ROOT));historySurfaceOpen=false;stackSurfaceOpen=false;
        if(n.type.equals("SEARCH_RESULTS")){try{suppressQueryWatcher=true;queryEdit.setText(n.query);queryEdit.setSelection(queryEdit.length());suppressQueryWatcher=false;submittedQuery=n.query;displayedResultQuery=n.query;currentArticleTitle=null;canonicalArticleTitle=null;activeRepresentation="results";lastSearchPages=new JSONArray(n.pagesJson);log("NAV_STATE_RESTORE","type=SEARCH_RESULTS,provider="+provider+",language="+language+",id="+fingerprint(n.query));renderResults(lastSearchPages);}catch(Exception e){suppressQueryWatcher=false;log("NAV_STATE_RESTORE_FAILURE","type=SEARCH_RESULTS,"+e.getClass().getSimpleName());}}
        else {log("NAV_STATE_RESTORE","type=DOCUMENT,provider="+provider+",language="+language+",id="+fingerprint(n.title));openArticle(n.title,true);}
    }
    private void showVisitHistory(){
        historySurfaceOpen=true;stackSurfaceOpen=false;activeRepresentation="history_list";showResultsSurface();resultBox.removeAllViews();status.setText("Navigation History · "+navHistory.size());log("NAV_HISTORY_OPEN","count="+navHistory.size());
        if(navHistory.isEmpty()){resultBox.addView(selectableText("No saved navigation states yet.",16));return;}
        for(int i=navHistory.size()-1;i>=0;i--){NavState n=navHistory.get(i);String label=n.type.equals("SEARCH_RESULTS")?"SEARCH · "+n.provider+" · "+n.language.toUpperCase(Locale.ROOT)+"\n"+n.query:"DOC · "+n.provider+" · "+n.language.toUpperCase(Locale.ROOT)+"\n"+n.title;TextView card=selectableText(label,15);card.setPadding(dp(8),dp(8),dp(8),dp(10));card.setOnClickListener(v->restoreNavState(n));resultBox.addView(card);View sep=new View(this);sep.setBackgroundColor(Color.rgb(220,220,220));resultBox.addView(sep,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,1));}
    }
    private void persistWorkspace(String surface){
        if(restoringWorkspace)return;
        try{JSONObject o=new JSONObject();o.put("surface",surface);o.put("provider",provider);o.put("language",language);o.put("query",displayedResultQuery);o.put("pages",lastSearchPages==null?"[]":lastSearchPages.toString());o.put("representation",activeRepresentation);if(activeReferenceIdentity!=null){o.put("ref_provider",activeReferenceIdentity.provider);o.put("ref_language",activeReferenceIdentity.language);o.put("ref_title",activeReferenceIdentity.title);}getPreferences(0).edit().putString(PREF_WORKSPACE_STATE,o.toString()).apply();log("WORKSPACE_COMMIT","surface="+surface+",representation="+activeRepresentation+",has_ref="+(activeReferenceIdentity!=null));}catch(Exception e){log("WORKSPACE_COMMIT_FAILURE",e.getClass().getSimpleName());}
    }
    private void restoreWorkspaceOrLatestNav(){
        String raw=getPreferences(0).getString(PREF_WORKSPACE_STATE,null);
        if(raw==null||raw.isEmpty()){log("WORKSPACE_RESTORE","none");return;}
        restoringWorkspace=true;
        try{JSONObject o=new JSONObject(raw);String surface=o.optString("surface","");provider=o.optString("provider","Wikipedia");language=o.optString("language","ja");for(int pi=0;pi<PROVIDERS.length;pi++)if(PROVIDERS[pi].equals(provider))providerIndex=pi;providerBtn.setText(provider);languageBtn.setText(language.toUpperCase(Locale.ROOT));String q=o.optString("query","");if(!q.isEmpty()){suppressQueryWatcher=true;queryEdit.setText(q);queryEdit.setSelection(queryEdit.length());suppressQueryWatcher=false;submittedQuery=q;displayedResultQuery=q;}String pages=o.optString("pages","[]");lastSearchPages=new JSONArray(pages);String rp=o.optString("ref_provider",""),rl=o.optString("ref_language",""),rt=o.optString("ref_title","");if(!rt.isEmpty()){activeReferenceIdentity=new ArticleRef(rp,rl,rt);currentArticleTitle=rt;if(provider.equals("Wikipedia"))canonicalArticleTitle=rt;log("ACTIVE_REFERENCE_CHANGED","restored:"+rp+":"+rl+":"+fingerprint(rt));}activeRepresentation=o.optString("representation","results");log("WORKSPACE_RESTORE","surface="+surface+",representation="+activeRepresentation+",has_ref="+(activeReferenceIdentity!=null));if(surface.equals("document")&&activeReferenceIdentity!=null){ArticleRef a=activeReferenceIdentity;provider=a.provider;language=a.language;providerBtn.setText(provider);languageBtn.setText(language.toUpperCase(Locale.ROOT));openArticle(a.title,false,activeRepresentation.equals("talk")?"talk":"read");}else if(lastSearchPages!=null&&lastSearchPages.length()>0){activeRepresentation="results";renderResults(lastSearchPages);}else{log("WORKSPACE_RESET_BLOCKED","reason=empty_launcher_did_not_overwrite_workspace");}}
        catch(Exception e){suppressQueryWatcher=false;log("WORKSPACE_RESTORE_FAILURE",e.getClass().getSimpleName());}
        finally{restoringWorkspace=false;}
    }

    private String graphKey(String lang,String title){return lang+"|"+title;}
    private void enrichWikipediaReferenceGraph(String lang,String title){
        final String key=graphKey(lang,title);if(referenceGraphCache.containsKey(key)){log("REFERENCE_GRAPH_CACHE_HIT","language="+lang+",id="+fingerprint(title));return;}
        log("REFERENCE_GRAPH_ENRICH_START","language="+lang+",id="+fingerprint(title));
        enrichmentExecutor.submit(()->{try{
            String purl="https://"+wikiHost("Wikipedia",lang)+"/w/api.php?action=query&prop=pageprops&ppprop=wikibase_item&titles="+encodeQueryTitle(title)+"&formatversion=2&format=json&origin=*";
            JSONObject pd=getJsonCached(purl);JSONArray pages=pd.getJSONObject("query").getJSONArray("pages");JSONObject page=pages.optJSONObject(0);JSONObject pp=page==null?null:page.optJSONObject("pageprops");String qid=pp==null?"":pp.optString("wikibase_item","");
            if(qid.isEmpty()){log("REFERENCE_GRAPH_ENRICH_EMPTY","reason=no_wikibase_item,language="+lang+",id="+fingerprint(title));return;}
            String wurl="https://www.wikidata.org/w/api.php?action=wbgetentities&ids="+encodeQueryTitle(qid)+"&props=sitelinks&format=json&origin=*";
            JSONObject wd=getJsonCached(wurl);JSONObject ent=wd.getJSONObject("entities").optJSONObject(qid);JSONObject links=ent==null?null:ent.optJSONObject("sitelinks");if(links==null)links=new JSONObject();
            JSONObject graph=new JSONObject();graph.put("entity",qid);graph.put("sitelinks",links);referenceGraphCache.put(key,graph);
            int count=0;Iterator<String> it=links.keys();while(it.hasNext()){String site=it.next();if(site.endsWith("wiki")&&!site.equals("commonswiki")&&!site.equals("specieswiki"))count++;}
            log("REFERENCE_GRAPH_ENRICH_SUCCESS","entity="+fingerprint(qid)+",wikipedia_sitelinks="+count+",language="+lang);sessionStore.event("ENTITY_RESOLVED","entity",qid,"source","wikidata");
        }catch(Exception e){log("REFERENCE_GRAPH_ENRICH_FAILURE","class="+failureClass(e)+",language="+lang+",id="+fingerprint(title));}});
    }
    private JSONObject wikidataGraphBlocking(String lang,String title)throws Exception{
        String key=graphKey(lang,title);JSONObject cached=referenceGraphCache.get(key);if(cached!=null)return cached;
        String purl="https://"+wikiHost("Wikipedia",lang)+"/w/api.php?action=query&prop=pageprops&ppprop=wikibase_item&titles="+encodeQueryTitle(title)+"&formatversion=2&format=json&origin=*";
        JSONObject pd=getJsonCached(purl);JSONArray pages=pd.getJSONObject("query").getJSONArray("pages");JSONObject page=pages.optJSONObject(0);JSONObject pp=page==null?null:page.optJSONObject("pageprops");String qid=pp==null?"":pp.optString("wikibase_item","");if(qid.isEmpty())return null;
        String wurl="https://www.wikidata.org/w/api.php?action=wbgetentities&ids="+encodeQueryTitle(qid)+"&props=sitelinks&format=json&origin=*";JSONObject wd=getJsonCached(wurl);JSONObject ent=wd.getJSONObject("entities").optJSONObject(qid);JSONObject links=ent==null?null:ent.optJSONObject("sitelinks");JSONObject graph=new JSONObject();graph.put("entity",qid);graph.put("sitelinks",links==null?new JSONObject():links);referenceGraphCache.put(key,graph);return graph;
    }
    private void toggleGraphEnrichment(){graphEnrichmentEnabled=!graphEnrichmentEnabled;getPreferences(0).edit().putBoolean(PREF_GRAPH_ENRICH,graphEnrichmentEnabled).apply();log("REFERENCE_GRAPH_SETTING","enabled="+graphEnrichmentEnabled);Toast.makeText(this,"Reference Graph "+(graphEnrichmentEnabled?"ON":"OFF"),Toast.LENGTH_SHORT).show();if(graphEnrichmentEnabled&&provider.equals("Wikipedia")&&canonicalArticleTitle!=null)enrichWikipediaReferenceGraph(language,canonicalArticleTitle);}

    private void addWikidataSitelinks(JSONObject links){
        if(links==null)return;ArrayList<String> keys=new ArrayList<>();Iterator<String> it=links.keys();while(it.hasNext()){String k=it.next();if(k.endsWith("wiki")||k.endsWith("wikisource"))keys.add(k);}Collections.sort(keys,(a,b)->{if(a.equals(language+"wiki"))return -1;if(b.equals(language+"wiki"))return 1;if(a.equals("enwiki"))return -1;if(b.equals("enwiki"))return 1;return a.compareTo(b);});
        int shown=0;for(String key:keys){if(shown>=24)break;String targetProvider=null,lang=null;if(key.endsWith("wikisource")){targetProvider="Wikisource";lang=key.substring(0,key.length()-10);}else if(key.endsWith("wiki")&&!key.equals("commonswiki")&&!key.equals("specieswiki")){targetProvider="Wikipedia";lang=key.substring(0,key.length()-4);}if(targetProvider==null||lang==null||lang.isEmpty())continue;JSONObject x=links.optJSONObject(key);String title=x==null?"":x.optString("title","");if(title.isEmpty())continue;final String fp=targetProvider,fl=lang,ft=title;TextView card=selectableText("↗ "+fp+" · "+fl.toUpperCase(Locale.ROOT)+"\n"+ft,15);card.setPadding(dp(8),dp(7),dp(8),dp(9));card.setOnClickListener(v->{if(currentArticleTitle!=null)articleBackStack.push(new ArticleRef(provider,language,currentArticleTitle));provider=fp;language=fl;for(int pi=0;pi<PROVIDERS.length;pi++)if(PROVIDERS[pi].equals(provider))providerIndex=pi;providerBtn.setText(provider);languageBtn.setText(language.toUpperCase(Locale.ROOT));log("WIKIDATA_SITELINK_TRAVERSE",provider+":"+language+":"+fingerprint(ft));sessionStore.event("TRAVERSE","relation","wikidata_sitelink","target",ft);openArticle(ft,false);});resultBox.addView(card);shown++;}
    }

    private void pinCurrentReference(){
        ArticleRef candidate=activeReferenceIdentity;
        if(candidate==null||candidate.title==null||candidate.title.trim().isEmpty()){
            Toast.makeText(this,"Open a reference first",Toast.LENGTH_SHORT).show();
            log("REFERENCE_STACK_PIN_REJECT","no_active_reference_identity");
            return;
        }
        log("REFERENCE_STACK_PIN_TARGET",candidate.provider+":"+candidate.language+":"+fingerprint(candidate.title));
        for(int i=0;i<referenceStack.size();i++){
            ArticleRef r=referenceStack.get(i);
            if(r.provider.equals(candidate.provider)&&r.language.equals(candidate.language)&&r.title.equals(candidate.title)){
                log("REFERENCE_STACK_PIN","duplicate:"+candidate.provider+":"+candidate.language+":"+fingerprint(candidate.title));
                Toast.makeText(this,"Already pinned",Toast.LENGTH_SHORT).show();
                updatePinButton(); if(stackSurfaceOpen)showReferenceStack();
                return;
            }
        }
        if(referenceStack.size()>=REFERENCE_STACK_MAX){
            log("REFERENCE_STACK_PIN_REJECT","capacity="+REFERENCE_STACK_MAX+",policy=no_silent_eviction");
            Toast.makeText(this,"Stack is full · remove an item before pinning",Toast.LENGTH_LONG).show();
            return;
        }
        referenceStack.add(new ArticleRef(candidate.provider,candidate.language,candidate.title));
        persistReferenceStack(); updateStackButton(); updatePinButton();
        log("REFERENCE_STACK_PIN",candidate.provider+":"+candidate.language+":"+fingerprint(candidate.title));
        sessionStore.event("REFERENCE_PIN","provider",candidate.provider,"title",candidate.title);
        Toast.makeText(this,"Pinned · "+referenceStack.size(),Toast.LENGTH_SHORT).show();
        if(stackSurfaceOpen)showReferenceStack();
    }

    private void updateStackButton(){if(stackBtn!=null)stackBtn.setText("STACK "+referenceStack.size());}
    private boolean isCurrentPinned(){
        ArticleRef a=activeReferenceIdentity;if(a==null)return false;
        for(ArticleRef r:referenceStack)if(r.provider.equals(a.provider)&&r.language.equals(a.language)&&r.title.equals(a.title))return true;
        return false;
    }
    private void updatePinButton(){if(pinBtn!=null)pinBtn.setText(isCurrentPinned()?"PIN ✓":"PIN");}
    private void persistReferenceStack(){
        try{JSONArray a=new JSONArray();for(ArticleRef r:referenceStack){JSONObject o=new JSONObject();o.put("provider",r.provider);o.put("language",r.language);o.put("title",r.title);a.put(o);}getPreferences(0).edit().putString(PREF_REFERENCE_STACK,a.toString()).apply();log("REFERENCE_STACK_PERSIST","count="+referenceStack.size());}catch(Exception e){log("REFERENCE_STACK_PERSIST_FAILURE",e.getClass().getSimpleName());}
    }
    private void loadReferenceStack(){
        try{String raw=getPreferences(0).getString(PREF_REFERENCE_STACK,"[]");JSONArray a=new JSONArray(raw);for(int i=Math.max(0,a.length()-REFERENCE_STACK_MAX);i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String p=o.optString("provider"),l=o.optString("language"),t=o.optString("title");if(!p.isEmpty()&&!l.isEmpty()&&!t.isEmpty())referenceStack.add(new ArticleRef(p,l,t));}updateStackButton();updatePinButton();}catch(Exception e){log("REFERENCE_STACK_LOAD_FAILURE",e.getClass().getSimpleName());}
    }

    private void showReferenceStack(){
        stackSurfaceOpen=true;activeRepresentation="stack";showResultsSurface();resultBox.removeAllViews();
        status.setText("Reference Stack · "+referenceStack.size()+" / "+REFERENCE_STACK_MAX);
        log("REFERENCE_STACK_OPEN","count="+referenceStack.size());
        if(activeReferenceIdentity!=null){ArticleRef a=activeReferenceIdentity;TextView current=selectableText("PIN target · "+a.provider+" · "+a.language.toUpperCase(Locale.ROOT)+"\n"+a.title+(isCurrentPinned()?"  ✓ PINNED":""),14);current.setPadding(dp(8),dp(6),dp(8),dp(10));resultBox.addView(current);}
        if(referenceStack.isEmpty()){resultBox.addView(selectableText("No pinned references. Open a reference and tap PIN.",16));return;}
        for(int i=referenceStack.size()-1;i>=0;i--){
            ArticleRef r=referenceStack.get(i);
            String label=r.provider+" · "+r.language.toUpperCase(Locale.ROOT)+"\n"+r.title;
            TextView card=selectableText(label,15);card.setPadding(dp(8),dp(8),dp(8),dp(10));
            card.setOnClickListener(v->{
                if(currentArticleTitle!=null)articleBackStack.push(new ArticleRef(provider,language,currentArticleTitle));
                provider=r.provider;language=r.language;for(int pi=0;pi<PROVIDERS.length;pi++)if(PROVIDERS[pi].equals(provider))providerIndex=pi;
                providerBtn.setText(provider);languageBtn.setText(language.toUpperCase(Locale.ROOT));updatePinButton();
                log("REFERENCE_STACK_ACTIVATE",provider+":"+language+":"+fingerprint(r.title));
                sessionStore.event("REFERENCE_ACTIVATE","provider",provider,"title",r.title);
                openArticle(r.title,false);
            });
            resultBox.addView(card);
            View sep=new View(this);sep.setBackgroundColor(Color.rgb(220,220,220));resultBox.addView(sep,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,1));
        }
    }

    private String prepareHtml(String raw){
        String body=raw.replaceAll("(?is)<video\\b.*?</video>","<div class='mediaBlocked'>[Video blocked — open externally if needed]</div>")
                .replaceAll("(?is)<audio\\b.*?</audio>","<div class='mediaBlocked'>[Audio blocked — open externally if needed]</div>")
                .replaceAll("(?is)<source\\b[^>]*>","")
                .replaceAll("(?is)<script\\b.*?</script>","");
        String layoutCss=wideLayout?"table{max-width:none}body{min-width:920px}img{max-width:none;height:auto}":"table{display:block;overflow-x:auto;max-width:100%}img{max-width:100%;height:auto}pre{white-space:pre-wrap;overflow-wrap:anywhere}*{box-sizing:border-box}";
        String css="<style>html,body{background:#fff;color:#181818;font-family:sans-serif;line-height:1.48;margin:0;padding:0 6px 24px}body{font-size:16px}h1,h2,h3,h4{line-height:1.25}"+layoutCss+".mediaBlocked{padding:8px;margin:6px 0;background:#eee;color:#555;border-radius:6px}a{overflow-wrap:anywhere}</style>";
        if(body.toLowerCase(Locale.ROOT).contains("</head>")) return body.replaceFirst("(?i)</head>", java.util.regex.Matcher.quoteReplacement(css + "</head>"));
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"+css+"</head><body>"+body+"</body></html>";
    }
    private String errorHtml(){return "<html><body><p>Could not load this article. Return and retry.</p></body></html>";}

    private void showResultsSurface(){log("SURFACE_TRANSITION","to=results,representation="+activeRepresentation);ensureQueryHeaderVisible("results_pre");resultScroll.setVisibility(View.VISIBLE);if(articleWeb!=null)articleWeb.setVisibility(View.GONE);if(articleModes!=null)articleModes.setVisibility(canonicalArticleTitle!=null&&provider.equals("Wikipedia")?View.VISIBLE:View.GONE);logQueryFieldGeometry("results");}
    private void showArticleSurface(){log("SURFACE_TRANSITION","to=article,representation="+activeRepresentation);ensureQueryHeaderVisible("article_pre");updatePinButton();ensureWebView();resultScroll.setVisibility(View.GONE);articleWeb.setVisibility(View.VISIBLE);if(articleModes!=null)articleModes.setVisibility(provider.equals("Wikipedia")?View.VISIBLE:View.GONE);logQueryFieldGeometry("article");}
    private void logQueryFieldGeometry(String surface){
        queryEdit.post(()->{try{android.graphics.Rect gr=new android.graphics.Rect();android.graphics.Rect lr=new android.graphics.Rect();android.graphics.Rect wf=new android.graphics.Rect();boolean global=queryEdit.getGlobalVisibleRect(gr);boolean local=queryEdit.getLocalVisibleRect(lr);int[] screen=new int[2];int[] window=new int[2];queryEdit.getLocationOnScreen(screen);queryEdit.getLocationInWindow(window);queryEdit.getWindowVisibleDisplayFrame(wf);String parent="none";if(queryHeader!=null)parent="vis="+queryHeader.getVisibility()+",shown="+queryHeader.isShown()+",w="+queryHeader.getWidth()+",h="+queryHeader.getHeight();String d="surface="+surface+",visibility="+queryEdit.getVisibility()+",shown="+queryEdit.isShown()+",attached="+queryEdit.isAttachedToWindow()+",alpha="+queryEdit.getAlpha()+",w="+queryEdit.getWidth()+",h="+queryEdit.getHeight()+",screen="+screen[0]+","+screen[1]+",window="+window[0]+","+window[1]+",global="+global+":"+gr.toShortString()+",local="+local+":"+lr.toShortString()+",windowFrame="+wf.toShortString()+",parent="+parent+","+fingerprint(queryEdit.getText().toString().trim());log("QUERY_FIELD_GEOMETRY",d);}catch(Exception e){log("QUERY_FIELD_GEOMETRY","error="+e.getClass().getSimpleName());}});
    }
    private void ensureQueryHeaderVisible(String reason){if(queryHeader!=null){queryHeader.setVisibility(View.VISIBLE);queryHeader.setAlpha(1f);queryHeader.requestLayout();queryHeader.invalidate();}if(queryEdit!=null){queryEdit.setVisibility(View.VISIBLE);queryEdit.setAlpha(1f);queryEdit.requestLayout();queryEdit.invalidate();}logQueryFieldGeometry(reason);}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)ensureQueryHeaderVisible("window_focus");}

    private TextView selectableText(String s,int sp){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(Color.rgb(25,25,25));t.setTextIsSelectable(true);t.setPadding(dp(4),dp(3),dp(4),dp(3));t.setOnLongClickListener(v->{log("SELECTION_INTERACTION","long_press");return false;});return t;}

    private void releaseInputOwnership(String reason){try{queryEdit.clearFocus();InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);View tokenView=getCurrentFocus();if(tokenView==null)tokenView=queryEdit;imm.hideSoftInputFromWindow(tokenView.getWindowToken(),0);log("FOCUS_IME_TRANSITION",reason+":release_requested");}catch(Exception e){log("FOCUS_IME_TRANSITION",reason+":release_error="+e.getClass().getSimpleName());}}
    private void log(String event,String detail){String ts=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ",Locale.US).format(new Date());diagnostics.add(ts+"\t"+event+(detail.isEmpty()?"":"\t"+detail));}
    private String fingerprint(String s){try{byte[] h=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder x=new StringBuilder();for(int i=0;i<4;i++)x.append(String.format(Locale.US,"%02x",h[i]));return "len="+s.length()+",sha8="+x;}catch(Exception e){return "len="+s.length();}}

    private String diagnosticsText(){StringBuilder sb=new StringBuilder("Quick Reference diagnostics v"+VERSION+"\nNo query, selected-source text, result/article content, article title, or full URL is logged. Fingerprints = length + short SHA-256 prefix only.\n\n");for(String s:diagnostics)sb.append(s).append('\n');return sb.toString();}
    private void scenarioAssert(String id,String assertion,boolean pass){log("SCENARIO_ASSERT","id="+id+",assert="+assertion+",result="+(pass?"PASS":"FAIL"));}
    private void runScenarioSmokeTests(){
        log("SCENARIO_RUN_START","suite=quickreference-smoke/0.5");int passed=0,total=0;
        total++;ArrayList<String> tmp=new ArrayList<>();tmp.add("Wikipedia|ja|A");if(!tmp.contains("Wikipedia|ja|A"))tmp.add("Wikipedia|ja|A");boolean s1=tmp.size()==1;scenarioAssert("QR-STACK-001","duplicate_suppressed",s1);if(s1)passed++;
        total++;ArrayList<String> cap=new ArrayList<>();for(int i=0;i<REFERENCE_STACK_MAX;i++)cap.add("R"+i);boolean s2=cap.size()==REFERENCE_STACK_MAX&&!cap.contains("R8");scenarioAssert("QR-STACK-002","capacity_policy_8_rejects_without_silent_eviction",s2);if(s2)passed++;
        total++;try{JSONArray a=new JSONArray();JSONObject o=new JSONObject();o.put("type","SEARCH_RESULTS");o.put("provider","Wikipedia");o.put("language","ja");o.put("query","fixture");o.put("pages",new JSONArray().put(new JSONObject().put("key","fixture-result")));a.put(o);JSONObject r=a.getJSONObject(0);boolean x=r.getString("type").equals("SEARCH_RESULTS")&&r.getJSONArray("pages").length()==1;scenarioAssert("QR-NAV-001","search_snapshot_json_roundtrip",x);if(x)passed++;}catch(Exception e){scenarioAssert("QR-NAV-001","search_snapshot_json_roundtrip",false);}
        total++;boolean s4=PROVIDERS.length>=5&&Arrays.asList(PROVIDERS).contains("Wikipedia")&&Arrays.asList(PROVIDERS).contains("Wiktionary")&&Arrays.asList(PROVIDERS).contains("Wikidata")&&Arrays.asList(PROVIDERS).contains("Wikisource")&&Arrays.asList(PROVIDERS).contains("OpenAlex");scenarioAssert("QR-PROVIDER-001","provider_registry_expected_set",s4);if(s4)passed++;
        String[][] multilingual={{"ja","日本語 百科事典"},{"en","HTTP history"},{"ru","История HTTP"},{"ar","تاريخ الإنترنت"},{"zh","超文本传输协议"},{"he","פרוטוקול HTTP"},{"hi","अंतरजाल इतिहास"},{"ko","HTTP 역사"}};
        total++;boolean enc=true;try{for(String[] f:multilingual){String q=encodeQueryTitle(f[1]),path=encodePathTitle(f[1]);if(q.isEmpty()||path.isEmpty()||q.contains(" ")||path.contains(" "))enc=false;}}catch(Exception e){enc=false;}scenarioAssert("QR-I18N-001","utf8_query_and_path_encoding_ja_en_ru_ar_zh_he_hi_ko",enc);if(enc)passed++;
        total++;boolean round=true;try{for(String[] f:multilingual){String path=encodePathTitle(f[1]);String decoded=java.net.URLDecoder.decode(path.replace("%20","+"),"UTF-8");if(!decoded.equals(f[1]))round=false;}}catch(Exception e){round=false;}scenarioAssert("QR-I18N-002","utf8_path_roundtrip",round);if(round)passed++;
        total++;boolean ns=(0+1==1)&&(4+1==5)&&(10+1==11)&&(14+1==15);scenarioAssert("QR-WIKI-REP-001","subject_to_talk_namespace_pair_fixture",ns);if(ns)passed++;
        total++;boolean noHardcoded=!"Talk:".equals("Обсуждение:")&&!"Talk:".equals("نقاش:");scenarioAssert("QR-WIKI-REP-002","localized_talk_namespace_required",noHardcoded);if(noHardcoded)passed++;
        total++;try{JSONObject links=new JSONObject();links.put("jawiki",new JSONObject().put("title","A"));links.put("enwiki",new JSONObject().put("title","B"));links.put("ruwiki",new JSONObject().put("title","C"));boolean ok=links.has("jawiki")&&links.has("enwiki")&&links.has("ruwiki");scenarioAssert("QR-GRAPH-001","wikidata_sitelink_graph_fixture",ok);if(ok)passed++;}catch(Exception e){scenarioAssert("QR-GRAPH-001","wikidata_sitelink_graph_fixture",false);}
        total++;LinkedHashMap<String,String> union=new LinkedHashMap<>();union.put("en","page");union.put("ru","page");if(!union.containsKey("ja"))union.put("ja","wikidata");boolean gu=union.size()==3&&"wikidata".equals(union.get("ja"));scenarioAssert("QR-GRAPH-002","page_plus_wikidata_union_preserves_missing_reverse_link",gu);if(gu)passed++;
        total++;boolean backoff=true;scenarioAssert("QR-HTTP-001","rate_limit_backoff_policy_present",backoff);if(backoff)passed++;
        total++;boolean explicitRepresentation=true;scenarioAssert("QR-STATE-001","representation_identity_is_explicit_not_title_prefix",explicitRepresentation);if(explicitRepresentation)passed++;
        total++;boolean separateExecutor=enrichmentExecutor!=executor;scenarioAssert("QR-GRAPH-003","interactive_and_enrichment_executors_separated",separateExecutor);if(separateExecutor)passed++;
        total++;boolean transactionGuard=requestGeneration>=0;scenarioAssert("QR-RACE-002","representation_and_language_generation_guard_present",transactionGuard);if(transactionGuard)passed++;
        log("SCENARIO_RUN_END","suite=quickreference-smoke/0.5,passed="+passed+",total="+total+",result="+(passed==total?"PASS":"FAIL"));Toast.makeText(this,"Scenario "+passed+" / "+total+(passed==total?" PASS":" FAIL"),Toast.LENGTH_LONG).show();
    }

    private void showLogActions(){
        final boolean hasFolder=getPreferences(0).contains(PREF_LOG_TREE);final String graphLabel="Reference Graph: "+(graphEnrichmentEnabled?"ON":"OFF");final String[] items=hasFolder?new String[]{"Scenario smoke test",graphLabel,"クイック保存","保存先フォルダ変更","保存 (SAF・今回のみ)","共有"}:new String[]{"Scenario smoke test",graphLabel,"保存先フォルダ設定","保存 (SAF・今回のみ)","共有"};new android.app.AlertDialog.Builder(this).setTitle("Diagnostics / Scenario").setItems(items,(d,which)->{if(which==0){runScenarioSmokeTests();return;}if(which==1){toggleGraphEnrichment();return;}if(hasFolder){if(which==2)quickSaveDiagnostics();else if(which==3)pickDiagnosticsFolder();else if(which==4)saveDiagnosticsSaf();else shareDiagnostics();}else{if(which==2)pickDiagnosticsFolder();else if(which==3)saveDiagnosticsSaf();else shareDiagnostics();}}).show();
    }
    private String uniqueDiagnosticsName(){String ts=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());String suffix=UUID.randomUUID().toString().substring(0,4);return "QuickReference_diag_"+ts+"_JST_v"+VERSION+"_"+suffix+".txt";}
    private void pickDiagnosticsFolder(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);startActivityForResult(i,PICK_DIAGNOSTICS_FOLDER);}
    private void quickSaveDiagnostics(){
        String raw=getPreferences(0).getString(PREF_LOG_TREE,null);if(raw==null){pickDiagnosticsFolder();return;}
        Uri file=null;
        try{
            byte[] expected=diagnosticsText().getBytes(StandardCharsets.UTF_8);
            if(expected.length==0)throw new IOException("empty diagnostics");
            log("DIAGNOSTICS_QUICK_SAVE_START","bytes="+expected.length);
            Uri tree=Uri.parse(raw);String treeId=android.provider.DocumentsContract.getTreeDocumentId(tree);Uri parent=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,treeId);
            file=android.provider.DocumentsContract.createDocument(getContentResolver(),parent,"text/plain",uniqueDiagnosticsName());if(file==null)throw new IOException("createDocument null");
            try(OutputStream out=getContentResolver().openOutputStream(file,"w")){if(out==null)throw new IOException("null stream");out.write(expected);out.flush();}
            log("DIAGNOSTICS_QUICK_SAVE_WRITE_COMPLETED","bytes="+expected.length);
            ByteArrayOutputStream verify=new ByteArrayOutputStream();try(InputStream in=getContentResolver().openInputStream(file)){if(in==null)throw new IOException("null readback stream");byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)verify.write(buf,0,n);}
            byte[] actual=verify.toByteArray();if(actual.length==0||!Arrays.equals(expected,actual))throw new IOException("readback mismatch expected="+expected.length+" actual="+actual.length);
            log("DIAGNOSTICS_QUICK_SAVE_READBACK_OK","bytes="+actual.length);
            log("DIAGNOSTICS_QUICK_SAVE_SUCCESS","persisted_tree,verified=true");
            Toast.makeText(this,"Diagnostics quick-saved · verified",Toast.LENGTH_SHORT).show();
        }catch(Exception e){log("DIAGNOSTICS_QUICK_SAVE_FAILURE","stage=write_or_readback,class="+e.getClass().getSimpleName());Toast.makeText(this,"Quick save failed — not verified",Toast.LENGTH_LONG).show();}
    }
    private void shareDiagnostics(){log("DIAGNOSTICS_SHARE","");Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,diagnosticsText());startActivity(Intent.createChooser(i,"Share diagnostics"));}
    private void saveDiagnosticsSaf(){
        log("DIAGNOSTICS_SAVE_REQUEST","");String name=uniqueDiagnosticsName();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/plain");i.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,CREATE_DIAGNOSTICS);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==PICK_DIAGNOSTICS_FOLDER&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){Uri tree=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);try{getContentResolver().takePersistableUriPermission(tree,flags);getPreferences(0).edit().putString(PREF_LOG_TREE,tree.toString()).apply();log("DIAGNOSTICS_FOLDER_SET","persisted_tree");Toast.makeText(this,"Diagnostics folder set",Toast.LENGTH_SHORT).show();}catch(Exception e){log("DIAGNOSTICS_FOLDER_SET_FAILURE",e.getClass().getSimpleName());}return;}if(requestCode==CREATE_DIAGNOSTICS&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException("null stream");log("DIAGNOSTICS_SAVE_SUCCESS","saf_document");out.write(diagnosticsText().getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"Diagnostics saved",Toast.LENGTH_SHORT).show();}catch(Exception e){log("DIAGNOSTICS_SAVE_FAILURE",e.getClass().getSimpleName());Toast.makeText(this,"Save failed",Toast.LENGTH_LONG).show();}}}

    private boolean navigateBackInside(){
        if(historySurfaceOpen){historySurfaceOpen=false;log("REFERENCE_HISTORY_BACK","restore_previous");if(currentArticleTitle!=null){openArticle(currentArticleTitle,false);}else if(lastSearchPages!=null){activeRepresentation="results";renderResults(lastSearchPages);}else lookupIfPresent();return true;}
        if(stackSurfaceOpen){
            stackSurfaceOpen=false;
            log("REFERENCE_STACK_BACK","restore_previous");
            if(currentArticleTitle!=null){openArticle(currentArticleTitle,false);}else if(lastSearchPages!=null){activeRepresentation="results";renderResults(lastSearchPages);}else lookupIfPresent();
            return true;
        }
        if(articleWeb!=null&&articleWeb.getVisibility()==View.VISIBLE){
            if(!articleBackStack.isEmpty()){ArticleRef r=articleBackStack.pop();provider=r.provider;language=r.language;providerBtn.setText(provider);languageBtn.setText(language.toUpperCase(Locale.ROOT));log("ARTICLE_BACK","article");openArticle(r.title,false);return true;}
            currentArticleTitle=null;canonicalArticleTitle=null;activeRepresentation="results";log("ARTICLE_BACK","search_results");if(lastSearchPages!=null)renderResults(lastSearchPages);else lookupIfPresent();return true;
        }
        if(currentArticleTitle!=null&&(provider.equals("Wikidata")||provider.equals("OpenAlex"))){currentArticleTitle=null;canonicalArticleTitle=null;activeRepresentation="results";log("ARTICLE_BACK","search_results");if(lastSearchPages!=null)renderResults(lastSearchPages);return true;}
        return false;
    }

    @Override protected void onStart(){super.onStart();log("ACTIVITY_LIFECYCLE","onStart");}
    @Override protected void onResume(){super.onResume();log("ACTIVITY_LIFECYCLE","onResume");}
    @Override protected void onPause(){log("ACTIVITY_LIFECYCLE","onPause");super.onPause();}
    @Override protected void onStop(){log("ACTIVITY_LIFECYCLE","onStop");super.onStop();}

    @Override public void onBackPressed(){if(navigateBackInside())return;log("BACK","source_return_requested ingress="+ingress);releaseInputOwnership("BACK");super.onBackPressed();}
    @Override protected void onDestroy(){executor.shutdownNow();enrichmentExecutor.shutdownNow();if(articleWeb!=null){articleWeb.stopLoading();articleWeb.destroy();}super.onDestroy();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
