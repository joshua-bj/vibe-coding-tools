package com.suzhouxpower.andriod.vibrationdemo;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class ViewPagerAdapter extends FragmentStateAdapter {

    public ViewPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1: return new RmsFragment();
            case 2: return new VelocityFragment();
            case 3: return new VoiceprintFragment();
            default: return new AccelerometerFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 4;
    }
}
