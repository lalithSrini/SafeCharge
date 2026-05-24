package com.example.safecharge;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AlertsFragment extends Fragment {

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private View btnDeleteSelected, btnSelectAll;
    private PrefManager pref;
    private final List<AlertEvent> alertList = new ArrayList<>();
    private AlertAdapter adapter;

    // Selection Mode fields
    private final Set<Long> selectedIds = new HashSet<>();
    private boolean isSelectionMode = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_alerts, container, false);
        pref = new PrefManager(requireContext());
        
        recyclerView = view.findViewById(R.id.recyclerAlerts);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        btnDeleteSelected = view.findViewById(R.id.btnDeleteSelected);
        btnSelectAll = view.findViewById(R.id.btnSelectAll);
        
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new AlertAdapter(alertList);
        recyclerView.setAdapter(adapter);

        btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
        btnSelectAll.setOnClickListener(v -> toggleSelectAll());
        
        return view;
    }

    private void toggleSelectAll() {
        if (selectedIds.size() == alertList.size()) {
            selectedIds.clear();
            exitSelectionMode();
        } else {
            for (AlertEvent event : alertList) {
                selectedIds.add(event.id);
            }
            isSelectionMode = true;
            updateSelectionUI();
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAlerts();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) refreshAlerts();
    }

    private void refreshAlerts() {
        exitSelectionMode(); // Exit selection mode on refresh
        alertList.clear();
        try {
            JSONArray array = new JSONArray(pref.getAlertHistoryJson());
            // Most recent first
            for (int i = array.length() - 1; i >= 0; i--) {
                JSONObject obj = array.getJSONObject(i);
                
                String type = obj.optString("type", "Unknown");
                long time = obj.optLong("time", 0);
                double lat = obj.optDouble("lat", 0);
                double lon = obj.optDouble("lon", 0);
                
                // FIX: Use 'time' as fallback ID if 'id' is missing (for old alerts)
                // to prevent selection collisions where all items share ID 0.
                long id = obj.optLong("id", time);
                
                List<String> photos = new ArrayList<>();
                JSONArray photoArray = obj.optJSONArray("photos");
                if (photoArray != null) {
                    for (int j = 0; j < photoArray.length(); j++) {
                        photos.add(photoArray.getString(j));
                    }
                }
                
                alertList.add(new AlertEvent(id, type, time, lat, lon, photos));
            }
        } catch (Exception ignored) {}

        if (alertList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
        }
    }

    private void toggleSelection(long id) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id);
        } else {
            selectedIds.add(id);
        }

        if (selectedIds.isEmpty()) {
            exitSelectionMode();
        } else {
            isSelectionMode = true;
            updateSelectionUI();
            adapter.notifyDataSetChanged();
        }
    }

    private void updateSelectionUI() {
        if (btnDeleteSelected instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) btnDeleteSelected)
                    .setText("DELETE (" + selectedIds.size() + ")");
        }
        btnDeleteSelected.setVisibility(View.VISIBLE);
        
        if (btnSelectAll instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) btnSelectAll)
                    .setText(selectedIds.size() == alertList.size() ? "DESELECT ALL" : "SELECT ALL");
        }
        btnSelectAll.setVisibility(View.VISIBLE);
    }

    private void exitSelectionMode() {
        isSelectionMode = false;
        selectedIds.clear();
        btnDeleteSelected.setVisibility(View.GONE);
        btnSelectAll.setVisibility(View.GONE);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void confirmDeleteSelected() {
        if (selectedIds.isEmpty()) return;

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Delete Selected")
                .setMessage("Remove " + selectedIds.size() + " security events from history?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    pref.removeAlertEvents(new HashSet<>(selectedIds));
                    refreshAlerts();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteAlert(AlertEvent event, int position) {
        if (isSelectionMode) return; // Disable single delete in selection mode

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Delete Alert")
                .setMessage("Remove this security event from history?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    pref.removeAlertEvent(event.id);
                    alertList.remove(position);
                    adapter.notifyItemRemoved(position);
                    if (alertList.isEmpty()) refreshAlerts();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDetailsDialog(AlertEvent event) {
        if (isSelectionMode) {
            toggleSelection(event.id);
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = getLayoutInflater().inflate(R.layout.dialog_alert_details, null);
        dialog.setContentView(view);

        TextView tvType = view.findViewById(R.id.tvType);
        TextView tvTime = view.findViewById(R.id.tvTime);
        TextView tvLoc  = view.findViewById(R.id.tvLocation);
        View btnMap     = view.findViewById(R.id.btnOpenMap);
        RecyclerView rv = view.findViewById(R.id.recyclerDetailsPhotos);
        TextView tvNo   = view.findViewById(R.id.tvNoPhotos);

        tvType.setText(event.type);
        tvTime.setText(new SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(new Date(event.time)));
        
        if (event.lat == 0 && event.lon == 0) {
            tvLoc.setText("GPS unavailable for this alert.");
            btnMap.setEnabled(false);
            btnMap.setAlpha(0.5f);
        } else {
            tvLoc.setText(String.format(Locale.US, "%.5f, %.5f", event.lat, event.lon));
            btnMap.setOnClickListener(v -> {
                String uri = String.format(Locale.US, "geo:0,0?q=%.6f,%.6f(Theft Alert)", event.lat, event.lon);
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                startActivity(intent);
            });
        }

        if (event.photos.isEmpty()) {
            rv.setVisibility(View.GONE);
            tvNo.setVisibility(View.VISIBLE);
        } else {
            rv.setVisibility(View.VISIBLE);
            tvNo.setVisibility(View.GONE);
            rv.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            rv.setAdapter(new PhotoThumbnailAdapter(event.photos));
        }

        view.findViewById(R.id.btnClose).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private static class AlertEvent {
        final long id;
        final String type;
        final long time;
        final double lat, lon;
        final List<String> photos;

        AlertEvent(long id, String type, long time, double lat, double lon, List<String> photos) {
            this.id = id;
            this.type = type;
            this.time = time;
            this.lat = lat;
            this.lon = lon;
            this.photos = photos;
        }
    }

    private class AlertAdapter extends RecyclerView.Adapter<AlertAdapter.AlertViewHolder> {
        private final List<AlertEvent> events;
        AlertAdapter(List<AlertEvent> events) { this.events = events; }

        @NonNull
        @Override
        public AlertViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_alert, parent, false);
            return new AlertViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull AlertViewHolder holder, int position) {
            AlertEvent event = events.get(position);
            holder.tvType.setText(event.type);
            
            SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a - dd MMM", Locale.getDefault());
            holder.tvTime.setText(sdf.format(new Date(event.time)));
            
            boolean isSelected = selectedIds.contains(event.id);
            
            // Visual selection feedback
            com.google.android.material.card.MaterialCardView card = (com.google.android.material.card.MaterialCardView) holder.itemView;
            if (isSelected) {
                card.setStrokeColor(ContextCompat.getColor(requireContext(), R.color.neon_red));
                card.setStrokeWidth(4);
                card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.bg_card_elevated));
            } else {
                card.setStrokeColor(ColorStateList.valueOf(0x1AFFFFFF));
                card.setStrokeWidth(2);
                card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.bg_surface));
            }

            holder.itemView.setOnClickListener(v -> showDetailsDialog(event));
            
            holder.itemView.setOnLongClickListener(v -> {
                toggleSelection(event.id);
                return true;
            });

            holder.btnDelete.setVisibility(isSelectionMode ? View.GONE : View.VISIBLE);
            holder.btnDelete.setOnClickListener(v -> confirmDeleteAlert(event, holder.getAdapterPosition()));
        }

        @Override
        public int getItemCount() { return events.size(); }

        class AlertViewHolder extends RecyclerView.ViewHolder {
            final TextView tvType, tvTime;
            final View btnDelete;
            AlertViewHolder(View v) {
                super(v);
                tvType = v.findViewById(R.id.tvAlertType);
                tvTime = v.findViewById(R.id.tvAlertTime);
                btnDelete = v.findViewById(R.id.btnDeleteAlert);
            }
        }
    }

    private class PhotoThumbnailAdapter extends RecyclerView.Adapter<PhotoThumbnailAdapter.ThumbViewHolder> {
        private final List<String> photos;
        PhotoThumbnailAdapter(List<String> photos) { this.photos = photos; }

        @NonNull
        @Override
        public ThumbViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_detail_photo, parent, false);
            return new ThumbViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ThumbViewHolder holder, int position) {
            String filename = photos.get(position);
            File file = new File(requireContext().getFilesDir(), "SafeCharge/" + filename);
            
            Glide.with(holder.itemView.getContext())
                    .load(file)
                    .centerCrop()
                    .into(holder.iv);

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), FullScreenPhotoActivity.class);
                intent.putExtra("photo_path", file.getAbsolutePath());
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() { return photos.size(); }

        class ThumbViewHolder extends RecyclerView.ViewHolder {
            final ImageView iv;
            ThumbViewHolder(View v) {
                super(v);
                iv = v.findViewById(R.id.ivThumbnail);
            }
        }
    }
}