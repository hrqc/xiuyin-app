/* Model protocol adapted from Andromr d8005852211026c5a67f6fededb6d4cede6680d5.
 * Original project: https://github.com/aicelen/Andromr (AGPL-3.0).
 * See assets/omr/NOTICE.txt and assets/omr/AGPL-3.0.txt. */
package com.xiuyin.app.omr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.TensorBuffer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Real CPU inference only. All models are local verified files. One worker owns this instance. */
public final class NativeOmrModels implements AutoCloseable {
    private static final String[] HEADS = {"rhythms", "pitchs", "lifts", "positions", "articulations", "slurs"};
    private static final String[] FIELDS = {"rhythm", "pitch", "lift", "position", "articulation", "slur"};
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final JSONObject vocabulary;
    private LiteModel segmenter;
    private LiteModel encoder;
    private OrtSession decoder;

    public NativeOmrModels(File directory, JSONObject vocabulary, int threads) throws Exception {
        this.vocabulary = vocabulary;
        try {
            segmenter = new LiteModel(new File(directory, "segnet_308_int8.tflite"), threads);
            encoder = new LiteModel(new File(directory, "encoder_414_int8.tflite"), threads);
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(threads);
                decoder = environment.createSession(new File(directory, "decoder_414_int8_scores.onnx").getAbsolutePath(), options);
            }
        } catch (Exception error) { try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); } throw error; }
    }

    public long[] segment(float[] nchw) throws Exception {
        if (nchw.length != 3 * 320 * 320) throw new IllegalArgumentException("分割输入尺寸无效");
        checkCancelled();
        long[] result = segmenter.runLong(nchw);
        if (result.length != 320 * 320) throw new IllegalStateException("分割输出尺寸无效");
        return result;
    }

    public JSONObject recognizeStaff(float[] pixels) throws Exception {
        if (pixels.length != 256 * 1280) throw new IllegalArgumentException("谱表输入尺寸无效");
        checkCancelled();
        float[] contextValues = encoder.runFloat(pixels);
        if (contextValues.length != 1280 * 512) throw new IllegalStateException("编码器输出尺寸无效");
        JSONArray tokens = new JSONArray();
        OnnxTensor[] initialCache = new OnnxTensor[32];
        OnnxTensor[] cache = new OnnxTensor[32];
        OrtSession.Result previousResult = null;
        boolean eos = false;
        long[] previous = {1, 0, 0, 0, 0, 0};
        try (OnnxTensor context = OnnxTensor.createTensor(environment, FloatBuffer.wrap(contextValues), new long[]{1,1280,512});
             OnnxTensor emptyContext = OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), new long[]{1,0,512})) {
            for (int i=0; i<32; i++) {
                initialCache[i] = OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), new long[]{1,8,0,64});
                cache[i] = initialCache[i];
            }
            for (int step=0; step<608; step++) {
                checkCancelled();
                List<OnnxTensor> ownedInputs = new ArrayList<>();
                OrtSession.Result result = null;
                try {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("context", step == 0 ? context : emptyContext);
                    for (int h=0; h<HEADS.length; h++) {
                        if (HEADS[h].equals("positions")) continue;
                        OnnxTensor token = OnnxTensor.createTensor(environment, new long[][]{{previous[h]}});
                        ownedInputs.add(token); inputs.put(HEADS[h], token);
                    }
                    OnnxTensor cacheLength = OnnxTensor.createTensor(environment, new long[]{step});
                    ownedInputs.add(cacheLength); inputs.put("cache_len", cacheLength);
                    for (int i=0; i<32; i++) inputs.put("cache_in"+i, cache[i]);
                    result = decoder.run(inputs);
                    // Previous results own caches. They can close only after this inference consumed them.
                    if (previousResult != null) { previousResult.close(); previousResult = null; }
                    JSONObject symbol = new JSONObject(); JSONObject scores = new JSONObject();
                    JSONObject alternatives = new JSONObject(); JSONArray ids = new JSONArray();
                    for (int h=0; h<HEADS.length; h++) {
                        OnnxTensor output = (OnnxTensor) result.get("out_"+HEADS[h]).orElseThrow();
                        long tokenId = output.getLongBuffer().get(0);
                        previous[h] = tokenId; ids.put(tokenId);
                        JSONArray words = vocabulary.getJSONArray(FIELDS[h]);
                        if (tokenId < 0 || tokenId >= words.length()) throw new IllegalStateException("模型返回未知符号");
                        symbol.put(FIELDS[h], words.getString((int)tokenId));
                        FloatBuffer logitsBuffer = ((OnnxTensor) result.get("logits_"+HEADS[h]).orElseThrow()).getFloatBuffer();
                        float[] logits = new float[logitsBuffer.remaining()]; logitsBuffer.get(logits);
                        if (logits.length != words.length()) throw new IllegalStateException("模型分数词表不匹配");
                        double max = -Double.MAX_VALUE; int argmax = -1;
                        for (int n=0; n<logits.length; n++) {
                            if (!Float.isFinite(logits[n])) throw new IllegalStateException("模型分数不是有限数");
                            if (logits[n] > max) {max=logits[n];argmax=n;}
                        }
                        if (argmax != tokenId) throw new IllegalStateException("概率输出与原模型符号不一致");
                        double sum=0; double[] probability = new double[logits.length];
                        for (int n=0; n<logits.length; n++) { probability[n]=Math.exp(logits[n]-max);sum+=probability[n]; }
                        scores.put(FIELDS[h], probability[(int)tokenId]/sum);
                        JSONArray top = new JSONArray();
                        for (int rank=0;rank<Math.min(3, probability.length);rank++) {
                            int best=0;for(int n=1;n<probability.length;n++)if(probability[n]>probability[best])best=n;
                            top.put(new JSONObject().put("token",words.getString(best)).put("modelScore",probability[best]/sum));probability[best]=-1;
                        }
                        alternatives.put(FIELDS[h],top);
                    }
                    if (previous[0] == 2) { eos=true; break; }
                    symbol.put("index",step).put("tokenIds",ids).put("modelScores",scores)
                            .put("alternatives",alternatives).put("confidenceCalibrated",false);
                    tokens.put(symbol);
                    for(int i=0;i<32;i++)cache[i]=(OnnxTensor)result.get("cache_out"+i).orElseThrow();
                    previousResult=result; result=null;
                } finally {
                    if(result!=null)result.close();
                    for(OnnxTensor value:ownedInputs)value.close();
                }
            }
        } finally {
            if(previousResult!=null)previousResult.close();
            for(OnnxTensor value:initialCache)if(value!=null)value.close();
        }
        return new JSONObject().put("tokens",tokens).put("eos",eos).put("sequenceTruncated",!eos);
    }

    public static void checkCancelled() throws InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("OMR 已取消");
    }

    @Override public void close() throws Exception {
        Exception failure=null;
        if(decoder!=null)try{decoder.close();}catch(Exception e){failure=e;}finally{decoder=null;}
        if(encoder!=null)try{encoder.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}finally{encoder=null;}
        if(segmenter!=null)try{segmenter.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}finally{segmenter=null;}
        if(failure!=null)throw failure;
    }

    private static final class LiteModel implements AutoCloseable {
        private CompiledModel model;
        private List<TensorBuffer> inputs;
        private List<TensorBuffer> outputs;
        LiteModel(File path,int threads) throws Exception {
            try {
                HashSet<Accelerator> accelerators=new HashSet<>();accelerators.add(Accelerator.CPU);
                CompiledModel.Options options=new CompiledModel.Options(accelerators);
                options.setCpuOptions(new CompiledModel.CpuOptions(threads,null,null));
                model=CompiledModel.create(path.getAbsolutePath(),options);
                inputs=model.createInputBuffers();outputs=model.createOutputBuffers();
            }catch(Exception error){close();throw error;}
        }
        void run(float[] input) throws Exception {inputs.get(0).writeFloat(input);model.run(inputs,outputs);}
        float[] runFloat(float[] input) throws Exception {run(input);return outputs.get(0).readFloat();}
        long[] runLong(float[] input) throws Exception {run(input);return outputs.get(0).readLong();}
        @Override public void close() {
            if(outputs!=null){for(TensorBuffer buffer:outputs)buffer.close();outputs=null;}
            if(inputs!=null){for(TensorBuffer buffer:inputs)buffer.close();inputs=null;}
            if(model!=null){model.close();model=null;}
        }
    }
}
