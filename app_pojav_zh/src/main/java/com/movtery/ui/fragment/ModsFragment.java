package com.movtery.ui.fragment;

import static com.movtery.utils.PojavZHTools.copyFileInBackground;
import static net.kdt.pojavlaunch.Tools.runOnUiThread;

import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.movtery.ui.subassembly.filelist.FileIcon;
import com.movtery.ui.subassembly.filelist.FileRecyclerAdapter;
import com.movtery.ui.subassembly.filelist.FileRecyclerView;
import com.movtery.utils.file.OperationFile;
import com.movtery.utils.file.PasteFile;
import com.movtery.ui.subassembly.filelist.FileSelectedListener;

import net.kdt.pojavlaunch.PojavApplication;
import com.movtery.utils.PojavZHTools;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import com.movtery.ui.dialog.FilesDialog;
import net.kdt.pojavlaunch.fragments.SearchModFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ModsFragment extends Fragment {
    public static final String TAG = "ModsFragment";
    public static final String BUNDLE_ROOT_PATH = "root_path";
    public static final String jarFileSuffix = ".jar";
    public static final String disableJarFileSuffix = ".jar.disabled";
    private ActivityResultLauncher<Object> openDocumentLauncher;
    private ImageButton mReturnButton, mAddModButton, mPasteButton, mDownloadButton, mSearchButton, mRefreshButton;
    private CheckBox mMultiSelectCheck, mSelectAllCheck;
    private FileRecyclerView mFileRecyclerView;
    private String mRootPath;

    public ModsFragment() {
        super(R.layout.fragment_mods);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openDocumentLauncher = registerForActivityResult(
                new OpenDocumentWithExtension("jar"),
                result -> {
                    if (result != null) {
                        Toast.makeText(requireContext(), getString(R.string.tasks_ongoing), Toast.LENGTH_SHORT).show();

                        PojavApplication.sExecutorService.execute(() -> {
                            copyFileInBackground(requireContext(), result, mRootPath);

                            runOnUiThread(() -> {
                                Toast.makeText(requireContext(), getString(R.string.zh_profile_mods_added_mod), Toast.LENGTH_SHORT).show();
                                mFileRecyclerView.refreshPath();
                            });
                        });
                    }
                }
        );
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        bindViews(view);
        parseBundle();
        mFileRecyclerView.setShowFiles(true);
        mFileRecyclerView.setShowFolders(false);
        mFileRecyclerView.lockAndListAt(new File(mRootPath), new File(mRootPath));

        mFileRecyclerView.setFileSelectedListener(new FileSelectedListener() {
            @Override
            public void onFileSelected(File file, String path) {
                showDialog(file);
            }

            @Override
            public void onItemLongClick(File file, String path) {
                if (file.isDirectory()) {
                    showDialog(file);
                }
            }
        });
        mFileRecyclerView.setOnMultiSelectListener(itemBeans -> {
            if (!itemBeans.isEmpty()) {
                PojavApplication.sExecutorService.execute(() -> {});
                //取出全部文件
                List<File> selectedFiles = new ArrayList<>();
                itemBeans.forEach(value -> {
                    File file = value.getFile();
                    if (file != null) {
                        selectedFiles.add(file);
                    }
                });
                FilesDialog.FilesButton filesButton = new FilesDialog.FilesButton();
                filesButton.setButtonVisibility(true, true, false, false, true, true);
                filesButton.setDialogText(getString(R.string.zh_file_multi_select_mode_title),
                        getString(R.string.zh_file_multi_select_mode_message, itemBeans.size()),
                        getString(R.string.zh_profile_mods_disable_or_enable));
                runOnUiThread(() -> {
                    FilesDialog filesDialog = new FilesDialog(requireContext(), filesButton, () -> runOnUiThread(() -> {
                        closeMultiSelect();
                        mFileRecyclerView.refreshPath();
                    }), selectedFiles);
                    filesDialog.setCopyButtonClick(() -> mPasteButton.setVisibility(View.VISIBLE));
                    filesDialog.setMoreButtonClick(() -> new OperationFile(requireContext(), () -> runOnUiThread(() -> {
                        closeMultiSelect();
                        mFileRecyclerView.refreshPath();
                    }), file -> {
                        if (file != null && file.exists()) {
                            String fileName = file.getName();
                            if (fileName.endsWith(jarFileSuffix)) {
                                disableMod(file);
                            } else if (fileName.endsWith(disableJarFileSuffix)) {
                                enableMod(file);
                            }
                        }
                    }).operationFile(selectedFiles));
                    filesDialog.show();
                });
            }
        });
        FileRecyclerAdapter adapter = mFileRecyclerView.getAdapter();
        mMultiSelectCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSelectAllCheck.setChecked(false);
            mSelectAllCheck.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            adapter.setMultiSelectMode(isChecked);
        });
        mSelectAllCheck.setOnCheckedChangeListener((buttonView, isChecked) -> adapter.selectAllFiles(isChecked));

        mReturnButton.setOnClickListener(v -> {
            closeMultiSelect();
            PojavZHTools.onBackPressed(requireActivity());
        });
        mAddModButton.setOnClickListener(v -> {
            closeMultiSelect();
            String suffix = ".jar";
            Toast.makeText(requireActivity(), String.format(getString(R.string.zh_file_add_file_tip), suffix), Toast.LENGTH_SHORT).show();
            openDocumentLauncher.launch(suffix);
        });
        mPasteButton.setOnClickListener(v -> PasteFile.getInstance().pasteFiles(requireActivity(), mFileRecyclerView.getFullPath(), this::getFileSuffix, () -> runOnUiThread(() -> {
            closeMultiSelect();
            mPasteButton.setVisibility(View.GONE);
            mFileRecyclerView.refreshPath();
        })));
        mDownloadButton.setOnClickListener(v -> {
            closeMultiSelect();
            Bundle bundle = new Bundle();
            bundle.putBoolean(SearchModFragment.BUNDLE_SEARCH_MODPACK, false);
            bundle.putString(SearchModFragment.BUNDLE_MOD_PATH, mRootPath);
            Tools.swapFragment(requireActivity(), SearchModFragment.class, SearchModFragment.TAG, bundle);
        });
        mSearchButton.setOnClickListener(v -> {
            closeMultiSelect();
            mFileRecyclerView.showSearchDialog();
        });
        mRefreshButton.setOnClickListener(v -> {
            closeMultiSelect();
            mFileRecyclerView.refreshPath();
        });
    }

    private void closeMultiSelect() {
        //点击其它控件时关闭多选模式
        mMultiSelectCheck.setChecked(false);
        mSelectAllCheck.setVisibility(View.GONE);
    }

    private void showDialog(File file) {
        String fileName = file.getName();

        FilesDialog.FilesButton filesButton = new FilesDialog.FilesButton();
        filesButton.setButtonVisibility(true, true, !file.isDirectory(), true, true, (fileName.endsWith(jarFileSuffix) || fileName.endsWith(disableJarFileSuffix)));
        if (file.isDirectory()) {
            filesButton.messageText = getString(R.string.zh_file_folder_message);
        } else {
            filesButton.messageText = getString(R.string.zh_file_message);
        }
        if (fileName.endsWith(jarFileSuffix))
            filesButton.moreButtonText = getString(R.string.zh_profile_mods_disable);
        else if (fileName.endsWith(disableJarFileSuffix))
            filesButton.moreButtonText = getString(R.string.zh_profile_mods_enable);

        FilesDialog filesDialog = new FilesDialog(requireContext(), filesButton, () -> runOnUiThread(() -> mFileRecyclerView.refreshPath()), file);

        filesDialog.setCopyButtonClick(() -> mPasteButton.setVisibility(View.VISIBLE));

        //检测后缀名，以设置正确的按钮
        if (fileName.endsWith(jarFileSuffix)) {
            filesDialog.setFileSuffix(jarFileSuffix);
            filesDialog.setMoreButtonClick(() -> {
                disableMod(file);
                mFileRecyclerView.refreshPath();
                filesDialog.dismiss();
            });
        } else if (fileName.endsWith(disableJarFileSuffix)) {
            filesDialog.setFileSuffix(disableJarFileSuffix);
            filesDialog.setMoreButtonClick(() -> {
                enableMod(file);
                mFileRecyclerView.refreshPath();
                filesDialog.dismiss();
            });
        }

        filesDialog.show();
    }

    private void disableMod(File file) {
        String fileName = file.getName();
        String fileParent = file.getParent();
        File newFile = new File(fileParent, fileName + ".disabled");
        PojavZHTools.renameFile(file, newFile);
    }

    private void enableMod(File file) {
        String fileName = file.getName();
        String fileParent = file.getParent();
        String newFileName = fileName.substring(0, fileName.lastIndexOf(disableJarFileSuffix));
        if (!fileName.endsWith(jarFileSuffix))
            newFileName += jarFileSuffix; //如果没有.jar结尾，那么默认加上.jar后缀

        File newFile = new File(fileParent, newFileName);
        PojavZHTools.renameFile(file, newFile);
    }

    private String getFileSuffix(File file) {
        String name = file.getName();
        if (name.endsWith(disableJarFileSuffix)) {
            return disableJarFileSuffix;
        } else if (name.endsWith(jarFileSuffix)) {
            return jarFileSuffix;
        } else {
            int dotIndex = file.getName().lastIndexOf('.');
            return dotIndex == -1 ? "" : file.getName().substring(dotIndex);
        }
    }

    private void parseBundle() {
        Bundle bundle = getArguments();
        if (bundle == null) return;
        mRootPath = bundle.getString(BUNDLE_ROOT_PATH, mRootPath);
    }

    private void bindViews(@NonNull View view) {
        mReturnButton = view.findViewById(R.id.zh_mods_return_button);
        mAddModButton = view.findViewById(R.id.zh_mods_add_mod_button);
        mPasteButton = view.findViewById(R.id.zh_mods_paste_button);
        mDownloadButton = view.findViewById(R.id.zh_mods_download_mod_button);
        mSearchButton = view.findViewById(R.id.zh_mods_search_button);
        mRefreshButton = view.findViewById(R.id.zh_mods_refresh_button);
        mMultiSelectCheck = view.findViewById(R.id.zh_mods_multi_select_files);
        mSelectAllCheck = view.findViewById(R.id.zh_mods_select_all);
        mFileRecyclerView = view.findViewById(R.id.zh_mods);

        mFileRecyclerView.setFileIcon(FileIcon.MOD);

        mPasteButton.setVisibility(PasteFile.getInstance().getPasteType() != null ? View.VISIBLE : View.GONE);

        PojavZHTools.setTooltipText(mReturnButton, mReturnButton.getContentDescription());
        PojavZHTools.setTooltipText(mAddModButton, mAddModButton.getContentDescription());
        PojavZHTools.setTooltipText(mPasteButton, mPasteButton.getContentDescription());
        PojavZHTools.setTooltipText(mDownloadButton, mDownloadButton.getContentDescription());
        PojavZHTools.setTooltipText(mSearchButton, mSearchButton.getContentDescription());
        PojavZHTools.setTooltipText(mRefreshButton, mRefreshButton.getContentDescription());
    }
}

