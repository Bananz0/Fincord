package org.akanework.gramophone.logic.utils.exoplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import org.akanework.gramophone.logic.utils.ReplayGainAudioProcessor
import org.akanework.gramophone.logic.utils.ReplayGainUtil
import org.nift4.alacdecoder.AlacRenderer

/**
 * The renderers, and the audio sink they share.
 *
 * **[context] is unwrapped to the application context before it reaches media3, and callers may
 * pass a Service freely because of it.** media3 1.11 hands the factory's context straight to
 * `DefaultMediaCodecAdapterFactory` and keeps it there, so a Service passed in is retained by the
 * renderers - which are retained by the player, which is retained by the shuffle order living in
 * the timeline, which `MediaSessionStub` holds in `lastOriginalTimeline` until the controller on
 * the far side of the binder is collected. LeakCanary caught that exact chain holding
 * `GramophonePlaybackService` alive past `onDestroy`. Everything media3 wants a context for here -
 * audio capabilities, codec lookup - is process-wide, so nothing is lost by it.
 */
@OptIn(UnstableApi::class)
class GramophoneRenderFactory(
	context: Context,
	private val replayGainAudioProcessor: ReplayGainAudioProcessor,
	configurationListener: (Format?) -> Unit = {},
	audioSinkListener: (DefaultAudioSink) -> Unit = {},
	// Automix's ghost player filters its own tail. Nothing is appended for the session player, so
	// the audio the listener normally hears passes through exactly the chain it did before.
	private val extraAudioProcessors: Array<AudioProcessor> = emptyArray(),
) : DefaultRenderersFactory(context.applicationContext) {

	/**
	 * Clearable, and that is the whole point of them being `var`.
	 *
	 * Passing `context.applicationContext` to the superclass fixed half of the leak this class's
	 * documentation describes; these listeners were the other half. `GramophonePlaybackService`
	 * supplies `::onAudioSinkInputFormatChanged`, a *bound* method reference, so the lambda holds
	 * the service in `CallableReference.receiver` - and this factory is reachable from the renderer
	 * it built, from the player, from the shuffle order in the timeline, from
	 * `MediaSessionStub.lastOriginalTimeline`. LeakCanary reported exactly that path after the
	 * context capture was closed:
	 *
	 * ```
	 * GramophoneRenderFactory.configurationListener
	 *   -> GramophonePlaybackService$buildLocalPlayer$player$1  (FunctionReferenceImpl)
	 *     -> CallableReference.receiver = GramophonePlaybackService
	 * ```
	 *
	 * A binder stub outlives the service by design and nothing here can change that, so the only
	 * fix available is to stop pointing at the service once it is gone. [detach] does that, from
	 * `onDestroy`.
	 */
	private var configurationListener: ((Format?) -> Unit)? = configurationListener
	private var audioSinkListener: ((DefaultAudioSink) -> Unit)? = audioSinkListener

	/**
	 * Drops the references to whoever built this factory.
	 *
	 * Safe to call while the player is still tearing down: the listeners exist to configure an
	 * output that is about to stop existing, and a null one simply means nobody is listening.
	 */
	fun detach() {
		configurationListener = null
		audioSinkListener = null
	}
	override fun buildAudioRenderers(
		context: Context,
		extensionRendererMode: Int,
		mediaCodecSelector: MediaCodecSelector,
		enableDecoderFallback: Boolean,
		audioSink: AudioSink,
		eventHandler: Handler,
		eventListener: AudioRendererEventListener,
		out: ArrayList<Renderer>,
	) {
		// Gramophone dae39af6d: prefer the bundled Java decoder. Several vendor ALAC codecs are
		// known to crash, reject valid files, or emit static for samples wider than 16 bits.
		out.add(AlacRenderer(eventHandler, eventListener, audioSink))
		super.buildAudioRenderers(
			context,
			extensionRendererMode,
			mediaCodecSelector,
			enableDecoderFallback,
			audioSink,
			eventHandler,
			eventListener,
			out,
		)
	}

	override fun buildAudioSink(
		context: Context,
		enableFloatOutput: Boolean,
		enableAudioTrackPlaybackParams: Boolean,
	): AudioSink {
		/*
		 * The setting is high-resolution passthrough, not "float output", and the difference is
		 * the whole reason it needs a comment. Verified against media3 1.11's DefaultAudioSink:
		 *
		 *     shouldUseFloatOutput(pcmEncoding) =
		 *         enableFloatOutput && Util.isEncodingHighResolutionPcm(pcmEncoding)
		 *
		 * so it can only ever engage for 24-bit and wider input; CD-quality material is untouched
		 * by it whatever the switch says. And where it does engage, configure() adds only
		 * toFloatPcmAudioProcessor and skips audioProcessorChain.getAudioProcessors() entirely -
		 * passthrough and processing are mutually exclusive by media3's design, not by ours.
		 *
		 * Hence the two guards. ReplayGain has to be able to reach the samples, so it wins; and a
		 * sink built with extra processors exists only to run them - the Automix ghost's bass swap
		 * would otherwise be silently dropped for exactly the hi-res tracks it was asked for.
		 * Neither guard costs anything on 16-bit audio, where none of this applies in the first
		 * place. This is the same conclusion nift4 reached upstream from the other direction; see
		 * the Gramophone audio-sink note in CLAUDE.md.
		 */
		val highResPassthrough = enableFloatOutput &&
			replayGainAudioProcessor.mode == ReplayGainUtil.Mode.None &&
			extraAudioProcessors.isEmpty()
		val root = DefaultAudioSink.Builder(context)
			.setAudioProcessors(arrayOf<AudioProcessor>(replayGainAudioProcessor) + extraAudioProcessors)
			.setEnableFloatOutput(highResPassthrough)
			.setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
			.build()
		audioSinkListener?.invoke(root)
		// Automix now overlaps two independently rendered players. Keeping the retired PCM-injection
		// sink here would add a hot-path wrapper to both of them without ever installing a deck.
		return ObservableAudioSink(root, highResPassthrough)
	}

	private inner class ObservableAudioSink(
		sink: AudioSink,
		/** Whether hi-res input will reach AudioTrack as float rather than being folded to 16-bit. */
		private val highResPassthrough: Boolean,
	) : ForwardingAudioSink(sink) {
		// media3 1.11 folded the configure arguments into one object and sealed the old signature
		// so an override of it cannot be quietly skipped. The input format is read from the config
		// and the config itself is passed on untouched, exactly as the three arguments were.
		override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
			val inputFormat = audioSinkConfig.format
			// Stock media3's processor API only exposes PCM shape, not source metadata. Update the
			// tags before DefaultAudioSink configures its processor chain.
			if (inputFormat.metadata != null) {
				replayGainAudioProcessor.setRootFormat(inputFormat)
			}
			// DefaultAudioSink turns high-resolution integer PCM into float when enabled, or 16-bit
			// otherwise. Negotiate the format AudioTrack will really use, not the decoder input.
			val outputFormat = if (Util.isEncodingHighResolutionPcm(inputFormat.pcmEncoding)) {
				inputFormat.buildUpon().setPcmEncoding(
					if (highResPassthrough) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
				).build()
			} else inputFormat
			// Preferred USB mixer attributes must exist before DefaultAudioSink creates AudioTrack.
			configurationListener?.invoke(outputFormat)
			super.configure(audioSinkConfig)
		}

		override fun reset() {
			configurationListener?.invoke(null)
			super.reset()
		}

		override fun release() {
			configurationListener?.invoke(null)
			super.release()
		}
	}
	override fun buildTextRenderers(
		context: Context,
		output: TextOutput,
		outputLooper: Looper,
		extensionRendererMode: Int,
		out: ArrayList<Renderer>
	) {
		// empty
	}

	override fun buildVideoRenderers(
		context: Context,
		extensionRendererMode: Int,
		mediaCodecSelector: MediaCodecSelector,
		enableDecoderFallback: Boolean,
		eventHandler: Handler,
		eventListener: VideoRendererEventListener,
		allowedVideoJoiningTimeMs: Long,
		out: java.util.ArrayList<Renderer>
	) {
		// empty
	}

	override fun buildImageRenderers(out: java.util.ArrayList<Renderer>) {
		// empty
	}

	override fun buildCameraMotionRenderers(
		context: Context,
		extensionRendererMode: Int,
		out: java.util.ArrayList<Renderer>
	) {
		// empty
	}
}
