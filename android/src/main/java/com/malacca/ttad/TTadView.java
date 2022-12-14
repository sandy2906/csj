package com.malacca.ttad;

import java.util.List;
import java.io.ByteArrayOutputStream;

import android.os.Build;
import android.view.View;
import android.util.Base64;
import android.text.TextUtils;
import android.content.Context;
import android.graphics.Bitmap;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import com.bytedance.sdk.openadsdk.AdSlot;
import com.bytedance.sdk.openadsdk.TTImage;
import com.bytedance.sdk.openadsdk.TTFeedAd;
import com.bytedance.sdk.openadsdk.TTSplashAd;
import com.bytedance.sdk.openadsdk.TTNativeAd;
import com.bytedance.sdk.openadsdk.FilterWord;
import com.bytedance.sdk.openadsdk.TTAdDislike;
import com.bytedance.sdk.openadsdk.TTAdNative;
import com.bytedance.sdk.openadsdk.TTAdManager;
import com.bytedance.sdk.openadsdk.TTAdConstant;
import com.bytedance.sdk.openadsdk.TTDrawFeedAd;
import com.bytedance.sdk.openadsdk.TTNativeExpressAd;
import com.bytedance.sdk.openadsdk.TTAppDownloadListener;

class TTadView extends FrameLayout implements LifecycleEventListener {
    private final static int API_LEVEL = Build.VERSION.SDK_INT;

    private TTadType adType;
    private String uuid = null;
    private String codeId = null;
    private boolean deepLink = false;
    private int timeout = 0;
    private int intervalTime = 0;
    private boolean dislikeNative = false;
    private boolean dislikeDisable = false;
    private boolean drawAdNeedLogo = false;
    private boolean canInterruptVideo = false;
    private ReadableMap listeners = null;

    private int adWidth = 0;
    private int adHeight = 0;
    private int adStatus = 0;

    private boolean adLoaded;
    private boolean jsUpdate;
    private boolean adReRender;
    private View drawClickView;
    private TTDrawFeedAd drawView;
    private TTSplashAd splashView;
    private TTNativeExpressAd adView;
    private ThemedReactContext rnContext;
    private RCTEventEmitter mEventEmitter;

    public TTadView(@NonNull Context context) {
        super(context);
    }

    public TTadView(@NonNull ThemedReactContext context, TTadType type) {
        super(context);
        adType = type;
        rnContext = context;
        mEventEmitter = context.getJSModule(RCTEventEmitter.class);
        context.addLifecycleEventListener(this);
    }

    /**
     * ????????? addView ???????????????????????????, ??????????????? adView ????????? `post(measureAndLayout)` ???????????????
     * ????????? banner ??????????????????????????????, ?????????, ???????????????, ???????????????????????????????????????
     * ????????????, ???????????? requestLayout ???????????????, ?????????????????? android, ????????????????????????, ???????????????
     * https://github.com/facebook/react-native/issues/17968
     * https://github.com/facebook/react-native/issues/11829
     * https://www.jianshu.com/p/a6c5042c5ce8
     */
    @Override
    public void requestLayout() {
        super.requestLayout();
        if (adLoaded) {
            post(measureAndLayout);
        }
    }

    private final Runnable measureAndLayout = new Runnable() {
        @Override
        public void run() {
            measure(
                    MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.EXACTLY)
            );
            layout(getLeft(), getTop(), getRight(), getBottom());
        }
    };

    // props ????????????, ????????????
    protected void updateAd(boolean reloadAd, boolean reloadSize) {
        // ??? jsUpdate ?????? size ???, ?????????
        if (jsUpdate) {
            jsUpdate = false;
            reloadSize = false;
        }
        if (reloadAd) {
            if (reloadSize) {
                // ????????? ad ??????, ??? size ?????????, ???????????????,
                // ??????????????????, ???????????????????????? onLayout ?????????
                adLoaded = false;
            } else {
                requestAd();
            }
        } else if (reloadSize && adType != TTadType.SPLASH && adType != TTadType.DRAW_NATIVE) {
            // ??? size ??????, express ?????????????????????, ad size ?????????????????????
            // ?????????, ????????????????????????
            adLoaded = false;
        }
    }

    // layout -> ???????????? && ???Loaded -> ??????
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int width = adLoaded ? 0 : getWidth();
        int height = adLoaded ? 0 : getHeight();
        if (width == 0 || (width == adWidth && height == adHeight)) {
            return;
        }
        adWidth = width;
        adHeight = height;
        requestAd();
    }

    // android 4.4 (API Level <= 19) ?????? react-navigation tabs ???????????????, ?????? tab ?????????
    // ????????????????????????, ???????????????, ????????????????????????, ??? adView ?????? render() ??????
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (API_LEVEL < 21 && !adReRender && adView != null) {
            adReRender = true;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (adReRender) {
            adView.render();
        }
    }

    // ?????????????????? uuid
    protected void setUUID(String uuid) {
        this.uuid = uuid;
    }

    // ????????????id
    protected void setCodeId(String codeId) {
        this.codeId = codeId;
    }

    // ???????????? deepLink
    protected void setDeepLink(boolean deepLink) {
        this.deepLink = deepLink;
    }

    // ?????????????????????????????????
    protected void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    // ????????????????????????
    protected void setIntervalTime(int intervalTime) {
        this.intervalTime = intervalTime;
        if (adView != null) {
            adView.setSlideIntervalTime(intervalTime);
        }
    }

    // ?????????????????? ???????????????????????? (??????, ????????????????????????, ????????????????????????)
    protected void setCanInterrupt(boolean canInterrupt) {
        canInterruptVideo = canInterrupt;
        if (adType == TTadType.DRAW_NATIVE) {
            if (drawView != null) {
                drawView.setCanInterruptVideoPlay(canInterrupt);
            }
        } else if (adView != null) {
            adView.setCanInterruptVideoPlay(canInterrupt);
        }
    }

    // draw_native ?????? onLoad ?????????????????? logo image ??? base64 ??????
    protected void setNeedAdLogo(boolean needAdLogo) {
        drawAdNeedLogo = needAdLogo;
    }

    // ????????????????????? dislike ??????(????????????), ????????????????????????(??????????????????)
    protected void setDislikeNative(boolean dislikeNative) {
        this.dislikeNative = dislikeNative;
        bindDislikeListener();
    }

    // ?????? dislike, ?????????????????????????????????
    protected void setDislikeDisable(boolean dislikeDisable) {
        this.dislikeDisable = dislikeDisable;
        if (!dislikeDisable) {
            bindDislikeListener();
        }
    }

    // ??????????????????
    protected void setListeners(ReadableMap listeners) {
        this.listeners = listeners;
        bindAdViewListener();
    }

    // ????????????
    private void requestAd() {
        // ??????????????????????????????
        if (adWidth == 0) {
            return;
        }
        adLoaded = true;

        // ?????? uuid ??????????????????, ????????? uuid ???????????????????????????, ????????????(?????????/??????props)?????????????????????
        if (!TextUtils.isEmpty(uuid)) {
            if (adType == TTadType.DRAW_NATIVE) {
                renderNativeDrawCache(uuid);
            } else {
                renderExpressCache(uuid, adType == TTadType.DRAW);
            }
            return;
        }

        // codeId ??????, ????????????, ???????????? js ??????
        if (TextUtils.isEmpty(codeId)) {
            sendAdEvent("onFail", -101, "TTad codeId not defined");
            return;
        }

        TTAdManager manager = TTadModule.get();
        if (manager == null) {
            sendAdEvent("onFail", -103, "TTad sdk not initialize");
            return;
        }
        try {
            TTAdNative mTTAdNative = manager.createAdNative(rnContext);
            AdSlot.Builder builder = new AdSlot.Builder()
                    .setCodeId(codeId)
                    .setSupportDeepLink(deepLink)
                    .setAdCount(1);
            if (adType == TTadType.SPLASH) {
                loadSplashAd(mTTAdNative, builder);
            } else if (adType == TTadType.DRAW_NATIVE) {
                loadNativeDrawAd(mTTAdNative, builder);
            } else {
                loadExpressAd(mTTAdNative, builder);
            }
        } catch (Throwable e) {
            // ??????????????????????????????, handle ???
            sendAdEvent("onFail", -104, e.getMessage());
        }
    }

    /**
     * splash ????????????
     */
    private void loadSplashAd(TTAdNative mTTAdNative, AdSlot.Builder builder) {
        builder.setImageAcceptedSize(adWidth, adHeight);
        mTTAdNative.loadSplashAd(builder.build(), new TTAdNative.SplashAdListener() {
            @Override
            public void onError(int code, String msg) {
                sendAdEvent("onFail", code, msg);
            }

            @Override
            public void onTimeout() {
                sendAdEvent("onFail", -105, "request splash ad timeout");
            }

            @Override
            public void onSplashAdLoad(TTSplashAd ttSplashAd) {
                // ?????? js
                WritableMap map = Arguments.createMap();
                map.putString("event", "onLoad");
                map.putInt("type", ttSplashAd.getInteractionType());
                sendEvent(map);

                splashView = ttSplashAd;
                bindAdViewListener();
                removeAllViews();
                addView(ttSplashAd.getSplashView());
            }
        }, Math.max(timeout, 1500));
    }

    /**
     * draw ?????????????????? ???????????????
     */
    private void loadNativeDrawAd(TTAdNative mTTAdNative, AdSlot.Builder builder) {
        builder.setImageAcceptedSize(adWidth, adHeight);
        mTTAdNative.loadDrawFeedAd(builder.build(), new TTAdNative.DrawFeedAdListener() {
            @Override
            public void onError(int code, String message) {
                sendAdEvent("onFail", code, message);
            }

            @Override
            public void onDrawFeedAdLoad(List<TTDrawFeedAd> ads) {
                if (ads == null || ads.size() == 0) {
                    sendAdEvent("onFail", -105, "expressAd response empty");
                } else {
                    renderNativeDrawAd(ads.get(0), false);
                }
            }
        });
    }

    // ?????????????????? native Draw ??????
    private void renderNativeDrawCache(String uuid) {
        TTDrawFeedAd ad = TTadModule.getNativeDrawPreAd(uuid);
        if (ad == null) {
            sendAdEvent("onFail", -105, "drawAd cache empty");
        } else {
            renderNativeDrawAd(ad, true);
        }
    }

    // ?????? draw ????????????, ???????????????????????????, ????????? ??????, ???????????????????????????, ??????????????????????????????????????????
    private void renderNativeDrawAd(TTDrawFeedAd ad, final boolean preload) {
        removeAllViews();

        // ???????????? onAdCreativeClick ????????? view
        drawView = ad;
        drawClickView = new View(rnContext);
        drawClickView.setLayoutParams(new LayoutParams(1, 1));
        addView(drawClickView);

        ad.setCanInterruptVideoPlay(canInterruptVideo);
        ad.registerViewForInteraction(TTadView.this, drawClickView, new TTNativeAd.AdInteractionListener() {
            @Override
            public void onAdShow(TTNativeAd ad) {
                onNativeDrawAdLoaded(drawView);
                // ?????? sdk ?????????????????? ????????? requestLayout ??????????????????
                if (preload) {
                    requestLayout();
                }
            }

            @Override
            public void onAdClicked(View view, TTNativeAd ad) {
                // ?????????????????????????????????, ????????????????????????
                onClickAd("onDrawAdClick");
            }

            @Override
            public void onAdCreativeClick(View view, TTNativeAd ad) {
                onClickAd("onDrawClick");
            }
        });
        bindAdViewListener();
        addView(ad.getAdView(), 0);
    }

    // ?????? js draw ????????????
    private void onNativeDrawAdLoaded(TTDrawFeedAd ad) {
        WritableMap map = Arguments.createMap();
        map.putString("event", "onLoad");
        map.putInt("imageMode", ad.getImageMode());
        map.putInt("type", ad.getInteractionType());
        map.putString("title", ad.getTitle());
        map.putString("description", ad.getDescription());

        if (drawAdNeedLogo) {
            Bitmap bitmap = ad.getAdLogo();
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream .toByteArray();
            String logo = Base64.encodeToString(byteArray, Base64.DEFAULT);
            map.putString("logo", logo);
        }

        TTImage iconImage = ad.getIcon();
        if (iconImage == null) {
            map.putNull("icon");
        } else {
            WritableMap icon = Arguments.createMap();
            icon.putInt("width", iconImage.getWidth());
            icon.putInt("height", iconImage.getHeight());
            icon.putString("url", iconImage.getImageUrl());
            map.putMap("icon", icon);
        }

        TTImage coverImage = ad.getVideoCoverImage();
        if (coverImage == null) {
            map.putNull("cover");
        } else {
            WritableMap cover = Arguments.createMap();
            cover.putInt("width", coverImage.getWidth());
            cover.putInt("height", coverImage.getHeight());
            cover.putString("url", coverImage.getImageUrl());
            map.putMap("cover", cover);
        }
        map.putString("source", ad.getSource());
        map.putString("buttonText", ad.getButtonText());

        map.putInt("appSize", ad.getAppSize());
        map.putInt("appScore", ad.getAppScore());
        map.putInt("appCommentNum", ad.getAppCommentNum());
        map.putDouble("duration", ad.getVideoDuration());
        map.putMap("mediaExtra", Arguments.makeNativeMap(ad.getMediaExtraInfo()));
        sendEvent(map);
    }

    // ??????????????? click ??????
    protected void clickNativeDrawAd() {
        if (drawClickView != null) {
            drawClickView.performClick();
        }
    }

    /**
     * ExpressAd ????????????
     */
    private void loadExpressAd(TTAdNative mTTAdNative, AdSlot.Builder builder) {
        builder.setExpressViewAcceptedSize(
                TTadModule.toDIPFromPixel(adWidth),
                TTadModule.toDIPFromPixel(adHeight)
        ).setImageAcceptedSize(600, 600);
        TTAdNative.NativeExpressAdListener listener = new TTAdNative.NativeExpressAdListener() {
            @Override
            public void onError(int code, String message) {
                sendAdEvent("onFail", code, message);
            }

            @Override
            public void onNativeExpressAdLoad(List<TTNativeExpressAd> ads) {
                if (ads == null || ads.size() == 0) {
                    sendAdEvent("onFail", -105, "expressAd response empty");
                } else {
                    renderExpressAd(ads.get(0));
                }
            }
        };
        if (adType == TTadType.BANNER) {
            mTTAdNative.loadBannerExpressAd(builder.build(), listener);
        } else if (adType == TTadType.INTERACTION) {
            mTTAdNative.loadInteractionExpressAd(builder.build(), listener);
        } else if (adType == TTadType.DRAW) {
            mTTAdNative.loadExpressDrawFeedAd(builder.build(), listener);
        } else {
            mTTAdNative.loadNativeExpressAd(builder.build(), listener);
        }
    }

    // ?????????????????? ExpressAd ??????
    private void renderExpressCache(String uuid, boolean isDraw) {
        TTNativeExpressAd ad = isDraw ? TTadModule.getDrawPreAd(uuid) : TTadModule.getFeedPreAd(uuid);
        if (ad == null) {
            sendAdEvent("onFail", -105, "expressAd cache empty");
        } else {
            renderExpressAd(ad);
        }
    }

    // ?????? ExpressAd ??????
    private void renderExpressAd(TTNativeExpressAd ad) {
        destroyAdView();
        adView = ad;
        bindDislikeListener();
        bindAdViewListener();
        ad.setExpressInteractionListener(new TTNativeExpressAd.ExpressAdInteractionListener() {
            @Override
            public void onAdShow(View view, int type) {
                if (adReRender) {
                    adReRender = false;
                } else {
                    sendAdEvent("onShow", type, null);
                }
            }

            @Override
            public void onAdClicked(View view, int type) {
                onClickAd();
            }

            @Override
            public void onRenderFail(View view, String msg, int code) {
                sendAdEvent("onFail", code, msg);
            }

            @Override
            public void onRenderSuccess(View view, float width, float height) {
                insertAdView(view, width, height);
            }
        });
        ad.setCanInterruptVideoPlay(canInterruptVideo);
        if (intervalTime != 0) {
            ad.setSlideIntervalTime(intervalTime);
        }
        ad.render();
    }

    // ???????????? view ????????????
    private void insertAdView(final View view, final float width, final float height) {
        removeAllViews();
        addView(view);

        // ?????? js
        jsUpdate = adHeight == 0;
        WritableMap map = Arguments.createMap();
        map.putString("event", "onLoad");
        map.putInt("width", (int) width);
        map.putInt("height", (int) height);
        map.putInt("imageMode", adView.getImageMode());
        map.putInt("interaction", adView.getInteractionType());
        map.putBoolean("update", jsUpdate); // ?????????????????????0, ?????? js ????????????
        sendEvent(map);
    }

    // ?????????????????????
    private void bindDislikeListener() {
        if (adView == null || (adType != TTadType.FEED && adType != TTadType.BANNER) || dislikeDisable) {
            return;
        }
        // ???????????????
        if (dislikeNative) {
            adView.setDislikeCallback(rnContext.getCurrentActivity(), new TTAdDislike.DislikeInteractionCallback() {
                @Override
                public void onSelected(int i, String s) {
                    sendAdDislikeEvent(s);
                }

                @Override
                public void onCancel() {
                }
            });
            return;
        }
        // ??????????????????
        List<FilterWord> words = adView.getFilterWords();
        if (words == null || words.isEmpty()) {
            return;
        }
        final TTadDislikeDialog dislikeDialog = new TTadDislikeDialog(rnContext, words);
        dislikeDialog.setOnDislikeItemClick(new TTadDislikeDialog.OnDislikeItemClick() {
            @Override
            public void onItemClick(FilterWord filterWord) {
                sendAdDislikeEvent(filterWord.getName());
            }
        });
        adView.setDislikeDialog(dislikeDialog);
    }

    // ????????????????????????????????? (????????????, ???????????????????????????, ????????? sdk ?????????????????? interface)
    private void bindAdViewListener() {
        switch (adType) {
            case SPLASH:
                bindSplashListener();
                break;
            case DRAW_NATIVE:
                bindDrawVideoListener();
                break;
            default:
                bindExpressVideoListener();
                break;
        }
    }

    // splash ????????????
    private void bindSplashListener() {
        if (splashView == null) {
            return;
        }
        if (hasListener("bindDownload") && splashView.getInteractionType() == TTAdConstant.INTERACTION_TYPE_DOWNLOAD) {
            splashView.setDownloadListener(makeDownloadListener());
        }
        if (!hasListener("bindClick")) {
            return;
        }
        splashView.setSplashInteractionListener(new TTSplashAd.AdInteractionListener() {
            @Override
            public void onAdShow(View view, int type) {
                sendAdEvent("onShow", type, null);
            }

            @Override
            public void onAdClicked(View view, int i) {
                onClickAd();
            }

            @Override
            public void onAdSkip() {
                sendAdEvent("onSkip");
            }

            @Override
            public void onAdTimeOver() {
                sendAdEvent("onTimeOver");
            }
        });
    }

    // native draw ????????????????????????
    private void bindDrawVideoListener() {
        if (drawView == null) {
            return;
        }
        if (hasListener("bindDownload") && drawView.getInteractionType() == TTAdConstant.INTERACTION_TYPE_DOWNLOAD) {
            drawView.setDownloadListener(makeDownloadListener());
        }
        if (hasListener("bindClick")) {
            drawView.setDrawVideoListener(new TTDrawFeedAd.DrawVideoListener() {
                @Override
                public void onClick() {
                    onClickAd();
                }

                @Override
                public void onClickRetry() {
                    sendAdEvent("onVideoRetry");
                }
            });
        }
        if (!hasListener("bindVideo")) {
            return;
        }
        drawView.setVideoAdListener(new TTFeedAd.VideoAdListener() {
            @Override
            public void onVideoLoad(TTFeedAd ttFeedAd) {
                sendAdEvent("onVideoLoad");
            }

            @Override
            public void onVideoError(int code, int extraCode) {
                sendVideoError(code, extraCode);
            }

            @Override
            public void onVideoAdStartPlay(TTFeedAd ttFeedAd) {
                sendAdEvent("onVideoPlay");
            }

            @Override
            public void onVideoAdPaused(TTFeedAd ttFeedAd) {
                sendAdEvent("onVideoPaused");
            }

            @Override
            public void onVideoAdContinuePlay(TTFeedAd ttFeedAd) {
                sendAdEvent("onVideoContinue");
            }

            @Override
            public void onProgressUpdate(long current, long duration) {
                sendVideoProgress(current, duration);
            }

            @Override
            public void onVideoAdComplete(TTFeedAd ttFeedAd) {
                sendAdEvent("onVideoComplete");
            }
        });
    }

    // Express ????????????????????????  ???????????????????????? video ????????????
    private void bindExpressVideoListener() {
        if (adView == null) {
            return;
        }
        if (hasListener("bindDownload") && adView.getInteractionType() == TTAdConstant.INTERACTION_TYPE_DOWNLOAD) {
            adView.setDownloadListener(makeDownloadListener());
        }
        if (!hasListener("bindVideo")) {
            return;
        }
        adView.setVideoAdListener(new TTNativeExpressAd.ExpressVideoAdListener() {
            @Override
            public void onVideoLoad() {
                sendAdEvent("onVideoLoad");
            }

            @Override
            public void onVideoError(int code, int extraCode) {
                sendVideoError(code, extraCode);
            }

            @Override
            public void onVideoAdStartPlay() {
                sendAdEvent("onVideoPlay");
            }

            @Override
            public void onVideoAdPaused() {
                sendAdEvent("onVideoPaused");
            }

            @Override
            public void onProgressUpdate(long current, long duration) {
                sendVideoProgress(current, duration);
            }

            @Override
            public void onVideoAdComplete() {
                sendAdEvent("onVideoComplete");
            }

            @Override
            public void onVideoAdContinuePlay() {
                sendAdEvent("onVideoContinue");
            }

            @Override
            public void onClickRetry() {
                sendAdEvent("onVideoRetry");
            }
        });
    }

    // ?????????????????????
    private TTAppDownloadListener makeDownloadListener() {
        return new TTAppDownloadListener() {
            @Override
            public void onIdle() {
                sendDownloadEvent("onIdle", null, null, -1, -1);
            }

            @Override
            public void onDownloadActive(long totalBytes, long currBytes, String fileName, String appName) {
                sendDownloadEvent("onDownloadActive", fileName, appName, totalBytes, currBytes);
            }

            @Override
            public void onDownloadPaused(long totalBytes, long currBytes, String fileName, String appName) {
                sendDownloadEvent("onDownloadPaused", fileName, appName, totalBytes, currBytes);
            }

            @Override
            public void onDownloadFailed(long totalBytes, long currBytes, String fileName, String appName) {
                sendDownloadEvent("onDownloadFailed", fileName, appName, totalBytes, currBytes);
            }

            @Override
            public void onDownloadFinished(long totalBytes, String fileName, String appName) {
                sendDownloadEvent("onDownloadFinished", fileName, appName, totalBytes, -1);
            }

            @Override
            public void onInstalled(String fileName, String appName) {
                sendDownloadEvent("onInstalled", fileName, appName, -1, -1);
            }
        };
    }

    // ???????????????
    private void onClickAd() {
        onClickAd("onClick");
    }

    private void onClickAd(String event) {
        adStatus = 1;
        sendAdEvent(event);
    }

    @Override
    public void onHostPause() {
        // ?????????????????? pause ???, ???????????? onOpen ??????
        // ?????????????????????h5?????? ??? app?????????
        if (adStatus == 1) {
            adStatus = 2;
            sendAdEvent("onOpen");
        }
    }

    @Override
    public void onHostResume() {
        // ??? pause ????????????, ???????????? onClose ??????
        if (adStatus == 2) {
            sendAdEvent("onClose");
        }
        adStatus = 0;
    }

    @Override
    public void onHostDestroy() {
        destroyAdView();
    }

    public void destroyAdView() {
        if (adView != null) {
            adView.destroy();
        }
    }

    private boolean hasListener(String event) {
        return listeners != null && listeners.hasKey(event) && listeners.getBoolean(event);
    }

    private void sendAdEvent(String event) {
        if (hasListener(event)) {
            sendAdEvent(event, 0, null);
        }
    }

    private void sendAdEvent(String event, int code, @Nullable String error) {
        if (hasListener(event)) {
            WritableMap params = Arguments.createMap();
            params.putString("event", event);
            params.putInt("code", code);
            params.putString("error", error);
            sendEvent(params);
        }
    }

    private void sendAdDislikeEvent(String dislike) {
        WritableMap params = Arguments.createMap();
        params.putString("event", "onDislike");
        params.putString("dislike", dislike);
        sendEvent(params);
    }

    private void sendVideoError(int code, int extraCode) {
        if (!hasListener("onVideoError")) {
            return;
        }
        WritableMap params = Arguments.createMap();
        params.putString("event", "onVideoError");
        params.putInt("code", code);
        params.putInt("extraCode", extraCode);
        sendEvent(params);
    }

    private void sendVideoProgress(long current, long duration) {
        if (!hasListener("onVideoProgress")) {
            return;
        }
        WritableMap params = Arguments.createMap();
        params.putString("event", "onVideoProgress");
        params.putDouble("current", current);
        params.putDouble("duration", duration);
        sendEvent(params);
    }

    private void sendDownloadEvent(String event, String fileName, String appName, long totalBytes, long currBytes) {
        if (hasListener(event)) {
            WritableMap params = Arguments.createMap();
            params.putString("event", event);
            params.putString("fileName", fileName);
            params.putString("appName", appName);
            params.putDouble("totalBytes", totalBytes);
            params.putDouble("currBytes", currBytes);
            sendEvent(params);
        }
    }

    private void sendEvent(WritableMap event) {
        mEventEmitter.receiveEvent(getId(), TTadViewManager.EVENT_NAME, event);
    }
}