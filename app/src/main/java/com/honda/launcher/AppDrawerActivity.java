package com.honda.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Plain static grid of installed apps. No animations. */
public class AppDrawerActivity extends Activity {

    private static class App { ResolveInfo ri; String label; }

    private final List<App> apps = new ArrayList<>();
    private PackageManager pm;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();

        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            App a = new App();
            a.ri = ri;
            a.label = String.valueOf(ri.loadLabel(pm));
            apps.add(a);
        }
        Collections.sort(apps, new Comparator<App>() {
            public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        TextView back = new TextView(this);
        back.setText("<  HOME");
        back.setTextColor(0xFFE4002B);
        back.setTextSize(22);
        back.setPadding(dp(16), dp(10), dp(16), dp(10));
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        root.addView(back);

        GridView g = new GridView(this);
        g.setNumColumns(GridView.AUTO_FIT);
        g.setColumnWidth(dp(220));
        g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        g.setAdapter(new Adapter());
        g.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                ResolveInfo ri = apps.get(pos).ri;
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Exception ignore) { }
            }
        });
        root.addView(g, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private class Adapter extends BaseAdapter {
        public int getCount() { return apps.size(); }
        public Object getItem(int i) { return apps.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView name;
            if (v == null) {
                row = new LinearLayout(AppDrawerActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                icon = new ImageView(AppDrawerActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
                name = new TextView(AppDrawerActivity.this);
                name.setTextColor(0xFFFFFFFF);
                name.setTextSize(18);
                name.setSingleLine(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(12);
                row.addView(name, lp);
                row.setTag(new View[]{icon, name});
            } else {
                row = (LinearLayout) v;
            }
            View[] t = (View[]) row.getTag();
            App a = apps.get(i);
            ((ImageView) t[0]).setImageDrawable(a.ri.loadIcon(pm));
            ((TextView) t[1]).setText(a.label);
            return row;
        }
    }
}
