package com.example.safecharge;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Evidence archive fragment. Displays captured photos in a grid.
 *
 * Uses Glide for optimized asynchronous image loading and background
 * file fetching to prevent UI lag.
 */
public class PhotosFragment extends Fragment {

    private static final String FOLDER = "SafeCharge";

    private RecyclerView recyclerView;
    private View btnDeleteAll, btnSelectAll;
    private TextView tvEmpty;
    private PhotoAdapter adapter;
    private final List<File> photoList = new ArrayList<>();
    
    // Selection Mode fields
    private final Set<File> selectedFiles = new HashSet<>();
    private boolean isSelectionMode = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_photos, container, false);
        
        recyclerView = view.findViewById(R.id.recyclerPhotos);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        btnDeleteAll = view.findViewById(R.id.btnDeleteAll);
        btnSelectAll = view.findViewById(R.id.btnSelectAll);
        
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new PhotoAdapter(photoList);
        recyclerView.setAdapter(adapter);

        btnDeleteAll.setOnClickListener(v -> {
            if (isSelectionMode) {
                confirmDeleteSelected();
            } else {
                showDeleteAllConfirmation();
            }
        });

        btnSelectAll.setOnClickListener(v -> toggleSelectAll());
        
        return view;
    }

    private void toggleSelectAll() {
        if (selectedFiles.size() == photoList.size()) {
            selectedFiles.clear();
            exitSelectionMode();
        } else {
            selectedFiles.addAll(photoList);
            isSelectionMode = true;
            updateSelectionUI();
            adapter.notifyDataSetChanged();
        }
    }

    private void confirmDeleteSelected() {
        if (selectedFiles.isEmpty()) return;

        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Selected")
                .setMessage("Remove " + selectedFiles.size() + " evidence photos?")
                .setPositiveButton("Delete", (dialog, which) -> deleteSelectedPhotos())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSelectedPhotos() {
        executor.execute(() -> {
            for (File f : selectedFiles) {
                if (f.exists()) f.delete();
            }
            mainHandler.post(() -> {
                Toast.makeText(requireContext(), selectedFiles.size() + " photos deleted", Toast.LENGTH_SHORT).show();
                loadPhotosAsync();
            });
        });
    }

    private void showDeleteAllConfirmation() {
        if (photoList.isEmpty()) return;

        new AlertDialog.Builder(requireContext())
                .setTitle("Delete All Photos")
                .setMessage("Are you sure you want to delete all " + photoList.size() + " captured photos?")
                .setPositiveButton("Delete All", (dialog, which) -> deleteAllPhotos())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAllPhotos() {
        executor.execute(() -> {
            File dir = new File(requireContext().getFilesDir(), FOLDER);
            File[] files = dir.listFiles(f -> f.getName().endsWith(".jpg"));
            if (files != null) {
                for (File f : files) f.delete();
            }
            mainHandler.post(() -> {
                Toast.makeText(requireContext(), "All photos deleted", Toast.LENGTH_SHORT).show();
                loadPhotosAsync();
            });
        });
    }

    private void toggleSelection(File file) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file);
        } else {
            selectedFiles.add(file);
        }

        if (selectedFiles.isEmpty()) {
            exitSelectionMode();
        } else {
            isSelectionMode = true;
            updateSelectionUI();
            adapter.notifyDataSetChanged();
        }
    }

    private void updateSelectionUI() {
        if (btnDeleteAll instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) btnDeleteAll)
                    .setText("DELETE (" + selectedFiles.size() + ")");
        }
        btnDeleteAll.setVisibility(View.VISIBLE);

        if (btnSelectAll instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) btnSelectAll)
                    .setText(selectedFiles.size() == photoList.size() ? "DESELECT ALL" : "SELECT ALL");
        }
        btnSelectAll.setVisibility(View.VISIBLE);
    }

    private void exitSelectionMode() {
        isSelectionMode = false;
        selectedFiles.clear();
        btnSelectAll.setVisibility(View.GONE);
        if (btnDeleteAll instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) btnDeleteAll).setText("DELETE ALL");
        }
        if (photoList.isEmpty()) btnDeleteAll.setVisibility(View.GONE);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        loadPhotosAsync();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            loadPhotosAsync();
        }
    }

    private void loadPhotosAsync() {
        exitSelectionMode(); // Reset selection on refresh
        executor.execute(() -> {
            List<File> photos = getPhotos();
            mainHandler.post(() -> {
                if (isAdded()) {
                    photoList.clear();
                    photoList.addAll(photos);
                    adapter.notifyDataSetChanged();
                    
                    if (photos.isEmpty()) {
                        tvEmpty.setVisibility(View.VISIBLE);
                        recyclerView.setVisibility(View.GONE);
                        btnDeleteAll.setVisibility(View.GONE);
                    } else {
                        tvEmpty.setVisibility(View.GONE);
                        recyclerView.setVisibility(View.VISIBLE);
                        if (!isSelectionMode) {
                            btnDeleteAll.setVisibility(View.VISIBLE);
                        }
                    }
                }
            });
        });
    }

    private List<File> getPhotos() {
        File dir = new File(requireContext().getFilesDir(), FOLDER);
        if (!dir.exists()) return new ArrayList<>();
        File[] files = dir.listFiles(f -> f.getName().endsWith(".jpg"));
        if (files == null) return new ArrayList<>();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    private class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {
        private final List<File> photos;
        PhotoAdapter(List<File> photos) { this.photos = photos; }

        @NonNull
        @Override
        public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View card = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo_card, parent, false);
            return new PhotoViewHolder(card);
        }

        @Override
        public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
            holder.bind(photos.get(position));
        }

        @Override
        public int getItemCount() { return photos.size(); }

        class PhotoViewHolder extends RecyclerView.ViewHolder {
            private final TextView tvCamera, tvTimestamp;
            private final ImageView imageView;

            PhotoViewHolder(View itemView) {
                super(itemView);
                tvCamera = itemView.findViewById(R.id.tvCamera);
                tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
                imageView = itemView.findViewById(R.id.photoImage);
            }

            void bind(File photo) {
                String name = photo.getName();
                tvCamera.setText(name.contains("front") ? "FRONT" : "BACK");
                tvTimestamp.setText(formatTimestamp(name));

                boolean isSelected = selectedFiles.contains(photo);
                com.google.android.material.card.MaterialCardView card = (com.google.android.material.card.MaterialCardView) itemView;
                
                if (isSelected) {
                    card.setStrokeColor(ContextCompat.getColor(requireContext(), R.color.neon_red));
                    card.setStrokeWidth(4);
                } else {
                    card.setStrokeColor(ColorStateList.valueOf(0x1AFFFFFF));
                    card.setStrokeWidth(2);
                }

                // Glide handles background decoding, caching, and cross-fades
                // to eliminate lag/jank while scrolling or switching tabs.
                Glide.with(itemView.getContext())
                        .load(photo)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(imageView);

                itemView.setOnClickListener(v -> {
                    if (isSelectionMode) {
                        toggleSelection(photo);
                    } else {
                        android.content.Intent intent = new android.content.Intent(itemView.getContext(), FullScreenPhotoActivity.class);
                        intent.putExtra("photo_path", photo.getAbsolutePath());
                        itemView.getContext().startActivity(intent);
                    }
                });

                itemView.setOnLongClickListener(v -> {
                    toggleSelection(photo);
                    return true;
                });
            }
        }
    }

    private String formatTimestamp(String filename) {
        try {
            String[] parts = filename.replace(".jpg", "").split("_");
            String date = parts[2], time = parts[3];
            return date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6)
                    + "  " + time.substring(0, 2) + ":" + time.substring(2, 4) + ":" + time.substring(4);
        } catch (Exception e) { return filename; }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}