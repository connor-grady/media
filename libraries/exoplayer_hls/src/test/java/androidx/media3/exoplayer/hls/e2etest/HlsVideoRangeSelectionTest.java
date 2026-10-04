/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.hls.e2etest;

import static androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CodecInfo;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * End-to-end tests for variant selection between an HDR variant and SDR variants that share its
 * {@code BANDWIDTH}.
 *
 * <p>The multivariant playlist lists an HEVC Main 10 variant with {@code VIDEO-RANGE=PQ}, followed
 * by HEVC Main and H.264 variants with {@code VIDEO-RANGE=SDR}, all with identical bandwidth
 * attributes. The attributes mirror the multivariant playlist Jellyfin 12.1 serves when it can
 * remux an HDR source and also offers SDR transcodes.
 */
@Config(sdk = 30)
@RunWith(AndroidJUnit4.class)
public final class HlsVideoRangeSelectionTest {

  private static final String MULTIVARIANT_PLAYLIST_URI =
      "asset:///media/hls/hdr-with-sdr-fallbacks/multivariant_playlist.m3u8";
  private static final String HDR_HEVC_CODECS = "hvc1.2.4.L153.B0,mp4a.40.2";

  /** The upper bound of the default initial estimate for Wi-Fi and Ethernet. */
  private static final long COLD_BITRATE_ESTIMATE = 4_300_000;

  /** An estimate that exceeds the bandwidth of every variant. */
  private static final long HIGH_BITRATE_ESTIMATE = 1_000_000_000;

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(
              ShadowMediaCodecConfig.CODEC_INFO_AAC,
              ShadowMediaCodecConfig.CODEC_INFO_AVC,
              new CodecInfo(
                  /* codecName= */ "media3.video.hevc",
                  MimeTypes.VIDEO_H265,
                  /* profileLevels= */ ImmutableList.of(
                      createCodecProfileLevel(
                          CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel61),
                      createCodecProfileLevel(
                          CodecProfileLevel.HEVCProfileMain10,
                          CodecProfileLevel.HEVCMainTierLevel61),
                      createCodecProfileLevel(
                          CodecProfileLevel.HEVCProfileMain10HDR10,
                          CodecProfileLevel.HEVCMainTierLevel61)),
                  /* colorFormats= */ ImmutableList.of(
                      CodecCapabilities.COLOR_FormatYUV420Flexible))),
          /* encoders= */ ImmutableList.of());

  @Test
  public void coldBitrateEstimate_selectsHdrVariant() throws Exception {
    Format format = getFirstMediaLoadFormat(COLD_BITRATE_ESTIMATE);

    assertThat(format.codecs).isEqualTo(HDR_HEVC_CODECS);
  }

  @Test
  public void highBitrateEstimate_selectsHdrVariant() throws Exception {
    Format format = getFirstMediaLoadFormat(HIGH_BITRATE_ESTIMATE);

    assertThat(format.codecs).isEqualTo(HDR_HEVC_CODECS);
  }

  /**
   * Prepares a player with the given initial bitrate estimate and returns the track format of the
   * first media segment it requests.
   */
  private static Format getFirstMediaLoadFormat(long initialBitrateEstimate) throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setBandwidthMeter(
                new DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(initialBitrateEstimate)
                    .build())
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    AtomicReference<Format> firstMediaLoadFormat = new AtomicReference<>();
    player.addAnalyticsListener(
        new AnalyticsListener() {
          @Override
          public void onLoadStarted(
              EventTime eventTime,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              int retryCount) {
            @Nullable Format trackFormat = mediaLoadData.trackFormat;
            if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA && trackFormat != null) {
              firstMediaLoadFormat.compareAndSet(/* expectedValue= */ null, trackFormat);
            }
          }
        });

    player.setMediaItem(MediaItem.fromUri(MULTIVARIANT_PLAYLIST_URI));
    player.prepare();
    advance(player).untilBackgroundThreadCondition(() -> firstMediaLoadFormat.get() != null);
    player.release();

    return firstMediaLoadFormat.get();
  }
}
