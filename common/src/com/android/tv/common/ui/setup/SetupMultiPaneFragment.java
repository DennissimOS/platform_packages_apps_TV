/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.tv.common.ui.setup;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import com.android.tv.common.R;

/**
 * A fragment for channel source info/setup.
 */
public abstract class SetupMultiPaneFragment extends SetupFragment {
    public static final int ACTION_DONE = 1;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(R.id.guided_step_fragment_container, getContentFragment()).commit();
        View doneButton = view.findViewById(R.id.button_done);
        if (needsDoneButton()) {
            doneButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View paramView) {
                    SetupActionHelper.onActionClick(SetupMultiPaneFragment.this, ACTION_DONE);
                }
            });
        } else {
            doneButton.setVisibility(View.GONE);
        }
        return view;
    }

    @Override
    protected int getLayoutResourceId() {
        return R.layout.fragment_setup_multi_pane;
    }

    abstract protected Fragment getContentFragment();

    protected boolean needsDoneButton() {
        return true;
    }
}