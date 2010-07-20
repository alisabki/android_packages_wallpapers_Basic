/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.wallpaper.nexus;

import static android.renderscript.Element.RGBA_8888;
import static android.renderscript.Element.RGB_565;
import static android.renderscript.ProgramStore.DepthFunc.ALWAYS;
import static android.renderscript.Sampler.Value.LINEAR;
import static android.renderscript.Sampler.Value.CLAMP;
import static android.renderscript.Sampler.Value.WRAP;

import com.android.wallpaper.R;
import com.android.wallpaper.RenderScriptScene;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.renderscript.Allocation;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.Sampler;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.Type;
import android.renderscript.ProgramStore.BlendDstFunc;
import android.renderscript.ProgramStore.BlendSrcFunc;
import android.view.SurfaceHolder;

import java.util.TimeZone;

class NexusRS extends RenderScriptScene {
    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;

    private float mXOffset;
    private ScriptC_Nexus mScript;

    public NexusRS(int width, int height) {
        super(width, height);

        mOptionsARGB.inScaled = false;
        mOptionsARGB.inPreferredConfig = Bitmap.Config.ARGB_8888;
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffset = xOffset;
        mScript.set_gXOffset(xOffset);
    }

    @Override
    public void start() {
        super.start();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height); // updates mWidth, mHeight

        // android.util.Log.d("NexusRS", String.format("resize(%d, %d)", width, height));
    }

    @Override
    protected ScriptC createScript() {
        mScript = new ScriptC_Nexus(mRS, mResources, R.raw.nexus, true);

        createProgramFragmentStore();
        createProgramFragment();
        createProgramVertex();
        createState();

        mScript.set_gTBackground(loadTexture(R.drawable.pyramid_background));
        mScript.set_gTPulse(loadTextureARGB(R.drawable.pulse));
        mScript.set_gTGlow(loadTextureARGB(R.drawable.glow));
        mScript.setTimeZone(TimeZone.getDefault().getID());
        mScript.invoke_initPulses();
        return mScript;
    }

    private void createState() {
        int mode;
        try {
            mode = mResources.getInteger(R.integer.nexus_mode);
        } catch (Resources.NotFoundException exc) {
            mode = 0; // standard nexus mode
        }

        mScript.set_gIsPreview(isPreview() ? 1 : 0);
        mScript.set_gMode(mode);
        mScript.set_gXOffset(0.f);
    }

    private Allocation loadTexture(int id) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mResources,
                id, RGB_565(mRS), false);
        allocation.uploadToTexture(0);
        return allocation;
    }

    private Allocation loadTextureARGB(int id) {
        Bitmap b = BitmapFactory.decodeResource(mResources, id, mOptionsARGB);
        final Allocation allocation = Allocation.createFromBitmap(mRS, b, RGBA_8888(mRS), false);
        allocation.uploadToTexture(0);
        return allocation;
    }


    private void createProgramFragment() {
        // sampler and program fragment for pulses
        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS);
        builder.setTexture(ProgramFragment.Builder.EnvMode.MODULATE,
                           ProgramFragment.Builder.Format.RGBA, 0);
        ProgramFragment pft = builder.create();
        pft.bindSampler(Sampler.WRAP_LINEAR(mRS), 0);
        mScript.set_gPFTexture(pft);

        // sampler and program fragment for background image
        builder = new ProgramFragment.Builder(mRS);
        builder.setTexture(ProgramFragment.Builder.EnvMode.MODULATE,
                           ProgramFragment.Builder.Format.RGB, 0);
        ProgramFragment pft565 = builder.create();
        pft565.bindSampler(Sampler.CLAMP_NEAREST(mRS), 0);
        mScript.set_gPFTexture565(pft565);
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
        builder.setDitherEnable(false);
        ProgramStore solid = builder.create();
        mRS.contextBindProgramStore(solid);

        builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE);
        mScript.set_gPSBlend(builder.create());
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

        ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS, null, null);
        pvb.setTextureMatrixEnable(true);
        ProgramVertex pv = pvb.create();
        pv.bindAllocation(mPvOrthoAlloc);
        mRS.contextBindProgramVertex(pv);
    }

    @Override
    public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
            boolean resultRequested) {

        final int dw = mWidth;
        final int bw = 960; // XXX: hardcoded width of background texture
        if (mWidth < mHeight) {
            // nexus.rs ignores the xOffset when rotated; we shall endeavor to do so as well
            x = (int) (x + mXOffset * (bw-dw));
        }

        // android.util.Log.d("NexusRS", String.format(
        //     "dw=%d, bw=%d, xOffset=%g, x=%d",
        //     dw, bw, mWorldState.xOffset, x));

        if ("android.wallpaper.tap".equals(action)) {
            mScript.invoke_addTap(x, y);
        } else if ("android.home.drop".equals(action)) {
            mScript.invoke_addTap(x, y);
        }
        return null;
    }
}
