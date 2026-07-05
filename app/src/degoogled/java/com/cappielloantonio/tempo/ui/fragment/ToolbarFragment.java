package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentToolbarBinding;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.ui.activity.MainActivity;

@UnstableApi
public class ToolbarFragment extends Fragment {
    private static final String TAG = "ToolbarFragment";

    private FragmentToolbarBinding bind;
    private MainActivity activity;
    private com.cappielloantonio.tempo.sonos.ui.SonosDeviceSelector sonosDeviceSelector;
    private com.cappielloantonio.tempo.sonos.discovery.SonosDiscovery sonosDiscovery;
    private MediaService mediaService;
    private boolean isBound = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            com.cappielloantonio.tempo.service.BaseMediaService.LocalBinder binder = (com.cappielloantonio.tempo.service.BaseMediaService.LocalBinder) service;
            mediaService = (MediaService) binder.getService();
            isBound = true;
            
            // Set media service on the device selector
            if (sonosDeviceSelector != null) {
                sonosDeviceSelector.setMediaService(mediaService);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            mediaService = null;
            if (sonosDeviceSelector != null) {
                sonosDeviceSelector.setMediaService(null);
            }
        }
    };

    public ToolbarFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.main_page_menu, menu);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentToolbarBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        // Initialize Sonos discovery and selector early
        if (getContext() != null) {
            sonosDiscovery = new com.cappielloantonio.tempo.sonos.discovery.SonosDiscovery(getContext());
            sonosDeviceSelector = new com.cappielloantonio.tempo.sonos.ui.SonosDeviceSelector(
                (android.app.Activity) getContext(),
                sonosDiscovery,
                null
            );
        }

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        // Bind to MediaService with custom action for direct binding
        Intent intent = new Intent(requireContext(), MediaService.class);
        intent.setAction("com.cappielloantonio.tempo.service.BIND_DIRECT");
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        // Unbind from MediaService
        if (isBound) {
            requireContext().unbindService(serviceConnection);
            isBound = false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_search) {
            activity.navController.navigate(R.id.searchFragment);
            return true;
        } else if (item.getItemId() == R.id.action_settings) {
            activity.navController.navigate(R.id.settingsFragment);
            return true;
        } else if (item.getItemId() == R.id.sonos_menu_item) {
            // Show Sonos device chooser
            if (sonosDeviceSelector != null) {
                sonosDeviceSelector.showDeviceChooser();
            }
            return true;
        }

        return false;
    }
}