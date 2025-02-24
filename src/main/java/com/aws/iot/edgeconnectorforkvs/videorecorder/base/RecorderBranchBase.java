/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aws.iot.edgeconnectorforkvs.videorecorder.base;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import com.aws.iot.edgeconnectorforkvs.videorecorder.model.ContainerType;
import com.aws.iot.edgeconnectorforkvs.videorecorder.model.RecorderCapability;
import com.aws.iot.edgeconnectorforkvs.videorecorder.util.ConfigMuxer;
import com.aws.iot.edgeconnectorforkvs.videorecorder.util.GstDao;
import com.aws.iot.edgeconnectorforkvs.videorecorder.util.MuxerProperty;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.PadLinkException;
import org.freedesktop.gstreamer.PadProbeReturn;
import org.freedesktop.gstreamer.PadProbeType;
import org.freedesktop.gstreamer.Pipeline;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Recorder pipeline branch base class.
 */
@Slf4j
public abstract class RecorderBranchBase {

    @Getter
    @AllArgsConstructor
    private static class TeeMetadata {
        private Element que;
        private RecorderCapability type;
    }

    @Getter
    private RecorderCapability capability;
    @Getter(AccessLevel.PROTECTED)
    private GstDao gstCore;
    @Getter(AccessLevel.PROTECTED)
    private Pipeline pipeline;
    private HashMap<Element, TeeMetadata> teeSrcInfo;
    private HashMap<Pad, Element> teeSrcPad2Tee;
    @Getter(AccessLevel.PRIVATE)
    @Setter(AccessLevel.PRIVATE)
    private boolean branchAttached;
    private Lock bindLock;
    private CountDownLatch deattachCnt;
    private AtomicBoolean autoBind;
    private HashSet<Pad> entryPadSet;

    @Getter(AccessLevel.PROTECTED)
    private Pad.PROBE teeBlockProbe;

    /**
     * Create and get the entry pad of audio path.
     *
     * @return audio entry pad
     */
    protected abstract Pad getEntryAudioPad();

    /**
     * Create and get the entry pad of video path.
     *
     * @return video entry pad
     */
    protected abstract Pad getEntryVideoPad();

    /**
     * Release the given entry pad of audio path.
     *
     * @param pad pad to release
     */
    protected abstract void relEntryAudioPad(Pad pad);

    /**
     * Release the given entry pad of video path.
     *
     * @param pad pad to release
     */
    protected abstract void relEntryVideoPad(Pad pad);

    /**
     * Notification for the branch when starting binding, subclass can ovrrride it.
     */
    protected void onBindBegin() {}

    /**
     * Notification for the branch when finishing binding, subclass can ovrrride it.
     */
    protected void onBindEnd() {}

    /**
     * Notification for the branch when starting unbinding, subclass can ovrrride it.
     */
    protected void onUnbindBegin() {}

    /**
     * Notification for the branch when finishing unbinding, subclass can ovrrride it.
     */
    protected void onUnbindEnd() {}

    /**
     * Constructor for RecorderBranchBase.
     *
     * @param cap branch capability
     * @param dao GStreamer data access object
     * @param pipeline GStreamer pipeline
     */
    public RecorderBranchBase(RecorderCapability cap, GstDao dao, Pipeline pipeline) {
        this.capability = cap;
        this.gstCore = dao;
        this.pipeline = pipeline;
        this.teeSrcInfo = new HashMap<>();
        this.teeSrcPad2Tee = new HashMap<>();
        this.branchAttached = false;
        this.bindLock = new ReentrantLock();
        this.autoBind = new AtomicBoolean(true);
        this.entryPadSet = new HashSet<>();

        // Unbind the upper part of the path
        this.teeBlockProbe = (teeSrcPad, info) -> {
            Pad quePadSink = this.gstCore.getPadPeer(teeSrcPad);

            // Unlink tee and queue when teeSrcPad is idle
            if (this.gstCore.unlinkPad(teeSrcPad, quePadSink)) {
                log.debug("Tee and queue unlinked.");
            } else {
                log.error("Tee and queue unlink failed.");
            }

            // Send EOS to queue
            this.gstCore.sendPadEvent(quePadSink, this.gstCore.newEosEvent());

            // Remove tee src pad
            log.debug("Tee pad is releasing.");
            this.gstCore.relElementRequestPad(this.teeSrcPad2Tee.get(teeSrcPad), teeSrcPad);
            log.debug("Tee pad is removed.");
            this.teeSrcPad2Tee.remove(teeSrcPad);

            this.deattachCnt.countDown();

            return PadProbeReturn.REMOVE;
        };
    }

    /**
     * Check if this branch will be attached to the recorder automatically.
     *
     * @return true if the recorder controls the auto bind flow
     */
    public boolean isAutoBind() {
        return this.autoBind.get();
    }

    /**
     * Set this branch to attach to the recorder automatically.
     *
     * @param toBind enable/disable auto bind
     */
    public void setAutoBind(boolean toBind) {
        this.autoBind.set(toBind);
    }

    /**
     * Link a given Pad to this branch.
     *
     * @param recorderElmSrc an element of recorder is going to link to this branch
     * @param capsToBind selection for linking video or audio pad of this branch
     */
    private void bindPath(Element recorderElmSrc, RecorderCapability capsToBind)
            throws IllegalArgumentException {
        Pad entryPadSink = null;

        // Only a video or a audio pad can be bound at each request
        if (capsToBind == RecorderCapability.AUDIO_ONLY) {
            if (this.capability == RecorderCapability.AUDIO_ONLY
                    || this.capability == RecorderCapability.VIDEO_AUDIO) {
                entryPadSink = this.getEntryAudioPad();
            } else {
                log.warn("Unsupported capability when binding branch: {}.", capsToBind);
            }
        }
        if (capsToBind == RecorderCapability.VIDEO_ONLY) {
            if (this.capability == RecorderCapability.VIDEO_ONLY
                    || this.capability == RecorderCapability.VIDEO_AUDIO) {
                entryPadSink = this.getEntryVideoPad();
            } else {
                log.warn("Unsupported capability when binding branch: {}.", capsToBind);
            }
        }

        Pad recorderSrcPad = this.gstCore.getElementRequestPad(recorderElmSrc, "src_%u");
        Element queueElm = this.gstCore.newElement("queue");
        Pad quePadSrc = this.gstCore.getElementStaticPad(queueElm, "src");
        Pad quePadSink = this.gstCore.getElementStaticPad(queueElm, "sink");

        this.gstCore.setElement(queueElm, "flush-on-eos", true);
        this.gstCore.setElement(queueElm, "leaky", 2);

        // Link elements
        this.gstCore.addPipelineElements(this.pipeline, queueElm);

        try {
            this.gstCore.linkPad(recorderSrcPad, quePadSink);
            log.debug("Recorder tee and branch queue linked.");
        } catch (PadLinkException e) {
            log.error("Recorder tee and branch queue link failed.");
        }

        try {
            this.gstCore.linkPad(quePadSrc, entryPadSink);
            log.debug("Branch queue and branch entry linked.");
        } catch (PadLinkException e) {
            log.error("Branch queue and branch entry link failed.");
        }

        this.gstCore.playElement(queueElm);

        // Add info
        this.teeSrcInfo.put(recorderElmSrc, new TeeMetadata(queueElm, capsToBind));
        this.teeSrcPad2Tee.put(recorderSrcPad, recorderElmSrc);
        this.entryPadSet.add(entryPadSink);
    }

    /**
     * Bind given tees to this branch.
     *
     * @param teeVideos video tees of recorder are going to link to this branch
     * @param teeAudios audio tees of recorder are going to link to this branch
     */
    public void bind(ArrayList<Element> teeVideos, ArrayList<Element> teeAudios) {
        this.bindLock.lock();
        try {
            if (!this.isBranchAttached()) {
                log.debug("Branch binds to recorder.");

                this.onBindBegin();

                // bind teeVideos
                if (teeVideos != null) {
                    for (int i = 0; i < teeVideos.size(); ++i) {
                        this.bindPath(teeVideos.get(i), RecorderCapability.VIDEO_ONLY);
                    }
                }

                // bind teeAudios
                if (teeAudios != null) {
                    for (int i = 0; i < teeAudios.size(); ++i) {
                        this.bindPath(teeAudios.get(i), RecorderCapability.AUDIO_ONLY);
                    }
                }

                this.onBindEnd();

                // Set branch attached
                this.setBranchAttached(true);
            } else {
                log.warn("Branch is already bound to recorder.");
            }
        } finally {
            this.bindLock.unlock();
        }
    }

    private void unbindLower(Element queElement, RecorderCapability cap) {
        Pad quePadSrc = this.gstCore.getElementStaticPad(queElement, "src");
        Pad entryPadSink = this.gstCore.getPadPeer(quePadSrc);

        // Unlink elements
        if (this.gstCore.unlinkPad(quePadSrc, entryPadSink)) {
            log.debug("Branch queue and branch entry unlinked.");
        } else {
            log.error("Branch queue and branch entry unlink failed");
        }

        log.debug("branch queue is stopping.");
        this.gstCore.stopElement(queElement);
        log.debug("branch queue is removed.");
        this.gstCore.removePipelineElements(this.pipeline, queElement);

        log.debug("branch entry pad is releasing.");
        if (cap == RecorderCapability.AUDIO_ONLY) {
            relEntryAudioPad(entryPadSink);
        } else {
            relEntryVideoPad(entryPadSink);
        }

        log.debug("branch entry pad is removed.");
        this.entryPadSet.remove(entryPadSink);
    }

    /**
     * Unbind this branch from the recorder.
     */
    public void unbind() {
        this.bindLock.lock();
        try {
            if (this.isBranchAttached()) {
                this.deattachCnt = new CountDownLatch(this.teeSrcInfo.size());

                for (Map.Entry<Element, TeeMetadata> info : this.teeSrcInfo.entrySet()) {
                    Element queueElm = info.getValue().getQue();
                    Pad quePadSink = this.gstCore.getElementStaticPad(queueElm, "sink");
                    Pad teePadSrc = this.gstCore.getPadPeer(quePadSink);

                    this.gstCore.addPadProbe(teePadSrc, PadProbeType.IDLE, this.teeBlockProbe);
                }

                log.debug("Waiting for queues detaching");
                try {
                    this.deattachCnt.await();
                    log.debug("All queues are detached.");
                } catch (InterruptedException e) {
                    log.error("deattachCnt InterruptedException: {}", e.getMessage());
                }
                this.deattachCnt = null;

                this.onUnbindBegin();

                for (Map.Entry<Element, TeeMetadata> info : this.teeSrcInfo.entrySet()) {
                    this.unbindLower(info.getValue().getQue(), info.getValue().getType());
                }
                this.teeSrcInfo.clear();

                this.onUnbindEnd();

                // Set branch deattached
                this.setBranchAttached(false);
            } else {
                log.warn("Branch is already unbound.");
            }
        } finally {
            this.bindLock.unlock();
        }
    }

    /**
     * Helper function to create a new muxer by the given type.
     *
     * @param type container type
     * @param isFilePath selection muxer properties for file path or app path
     * @return a muxer element
     * @throws IllegalArgumentException if type is not supported
     */
    protected Element getMuxerFromType(ContainerType type, boolean isFilePath)
            throws IllegalArgumentException {

        Element muxer = null;

        if (ConfigMuxer.CONTAINER_INFO.containsKey(type)) {
            MuxerProperty conf = ConfigMuxer.CONTAINER_INFO.get(type);
            ArrayList<HashMap<String, Object>> propList = new ArrayList<>();

            muxer = this.gstCore.newElement(conf.getGstElmName());

            propList.add(conf.getGeneralProp());
            if (isFilePath) {
                propList.add(conf.getFilePathProp());
            } else {
                propList.add(conf.getAppPathProp());
            }

            for (HashMap<String, Object> properties : propList) {
                for (Map.Entry<String, Object> property : properties.entrySet()) {
                    this.gstCore.setElement(muxer, property.getKey(), property.getValue());
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported muxer container type: " + type);
        }

        return muxer;
    }

    /**
     * Helper function to extension name by the given type.
     *
     * @param type container type
     * @return file extension name
     * @throws IllegalArgumentException if type is not supported
     */
    protected String getFileExtensionFromType(ContainerType type) throws IllegalArgumentException {
        if (ConfigMuxer.CONTAINER_INFO.containsKey(type)) {
            return ConfigMuxer.CONTAINER_INFO.get(type).getFileExt();
        } else {
            throw new IllegalArgumentException("Unsupported extension container type: " + type);
        }
    }
}
