// Voice activity detection with the Silero VAD model (runs offline, in this window).
// Whisper invents text ("Thank you.", "Okay.") when it gets silence or background noise,
// so we only send audio to Whisper when this detector heard a real voice.
const Vad = (() => {
  const ORT_DIR = new URL('../../node_modules/onnxruntime-web/dist/', location.href).href;
  const MODEL_URL = '../../assets/silero_vad_v5.onnx';
  const FRAME = 512;          // samples per check at 16 kHz = 32 ms
  const SPEECH_PROB = 0.5;    // a frame counts as speech above this probability

  let session = null;
  let loading = null;

  function load() {
    loading ||= (async () => {
      ort.env.wasm.numThreads = 1;
      ort.env.wasm.wasmPaths = ORT_DIR;
      const model = await (await fetch(MODEL_URL)).arrayBuffer();
      session = await ort.InferenceSession.create(model, { executionProviders: ['wasm'] });
      return session;
    })();
    return loading;
  }

  // One stream per recording: push 512-sample frames (16 kHz), then ask for the result.
  // onFrame(frame, prob) is called for every frame in order (live typing uses it to find pauses).
  function createStream(onFrame) {
    let state = new ort.Tensor('float32', new Float32Array(2 * 128), [2, 1, 128]);
    const sr = new ort.Tensor('int64', BigInt64Array.from([16000n]), []);
    let queue = Promise.resolve();
    let speechMs = 0;
    let maxProb = 0;
    return {
      push(frame) {
        queue = queue.then(async () => {
          const out = await session.run({ input: new ort.Tensor('float32', frame, [1, FRAME]), state, sr });
          state = out.stateN;
          const p = out.output.data[0];
          maxProb = Math.max(maxProb, p);
          if (p > SPEECH_PROB) speechMs += 32;
          onFrame?.(frame, p);
        }).catch(() => {});
      },
      async result() {
        await queue;
        return { speechMs, maxProb };
      },
    };
  }

  return { load, createStream, FRAME, SPEECH_PROB, get ready() { return !!session; } };
})();
