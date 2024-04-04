package net.kdt.pojavlaunch.dialog;

import static net.kdt.pojavlaunch.PojavZHTools.deleteFileListener;
import static net.kdt.pojavlaunch.PojavZHTools.renameFileListener;
import static net.kdt.pojavlaunch.PojavZHTools.shareFile;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.kdt.pickafile.FileListView;

import net.kdt.pojavlaunch.R;

import java.io.File;

public class FilesDialog extends Dialog {
    private TextView mMessage;
    private Button mCancelButton, mMoreButton, mShareButton, mRenameButton, mDeleteButton;
    private boolean mCancel, mMore, mShare, mRename, mDelete;

    public FilesDialog(@NonNull Context context) {
        super(context);
        this.mCancel = false;
        this.mShare = false;
        this.mRename = false;
        this.mDelete = false;
        this.mMore = false;

        this.setCancelable(false);
        this.setContentView(R.layout.dialog_update);
        init();
    }

    private void init() {
        this.mMessage = findViewById(R.id.zh_files_message);
        this.mCancelButton = findViewById(R.id.zh_files_cancel);
        this.mShareButton = findViewById(R.id.zh_files_share);
        this.mRenameButton = findViewById(R.id.zh_files_rename);
        this.mDeleteButton = findViewById(R.id.zh_files_delete);
        this.mMoreButton = findViewById(R.id.zh_files_more);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.mCancelButton.setVisibility(mCancel ? View.VISIBLE : View.GONE);
        this.mShareButton.setVisibility(mShare ? View.VISIBLE : View.GONE);
        this.mRenameButton.setVisibility(mRename ? View.VISIBLE : View.GONE);
        this.mDeleteButton.setVisibility(mDelete ? View.VISIBLE : View.GONE);
        this.mMoreButton.setVisibility(mMore ? View.VISIBLE : View.GONE);
    }

    public void setMessageText(String message) {
        this.mMessage.setText(message);
    }

    public void setCancelButton() {
        this.mCancel = true;
        this.mCancelButton.setOnClickListener(view -> FilesDialog.this.dismiss());
    }

    public void setShareButton(Context context, File file) {
        this.mShare = true;
        this.mShareButton.setOnClickListener(view -> shareFile(context, file.getName(), file.getAbsolutePath()));
    }

    public void setRenameButton(Activity activity, FileListView mFileListView, File file) {
        this.mRename = true;
        this.mRenameButton.setOnClickListener(view -> renameFileListener(activity, mFileListView, file, false));
    }

    public void setDeleteButton(Activity activity, FileListView mFileListView, File file) {
        this.mDelete = true;
        this.mDeleteButton.setOnClickListener(view -> deleteFileListener(activity, mFileListView, file, false));
    }

    public void setMoreButton(String name, OnClickListener click) {
        this.mMore = true;
        this.mMoreButton.setText(name);
        this.mMoreButton.setOnClickListener((View.OnClickListener) click);
    }
}
