package com.limelight.binding.video;

import android.content.Context;

public class PerformanceInfo {

    public Context context;
    public String decoder;
    public int initialWidth;
    public int initialHeight;
    public float totalFps;
    public float receivedFps;
    public float renderedFps;
    public float lostFrameRate;
    public long rttInfo;
    public int framesWithHostProcessingLatency;
    public float minHostProcessingLatency;
    public float maxHostProcessingLatency;
    public float aveHostProcessingLatency;
    public float decodeTimeMs;
    public String bandWidth;


}
