#!/bin/bash
# Exports the Grafana dashboard to the provisioned JSON file.
#
# USAGE (pick one):
#
#   1) From a downloaded file (click "Save JSON to file" in Grafana):
#      bash monitoring/export-dashboard.sh ~/Downloads/dashboard.json
#
#   2) From the API (if dashboard was saved to Grafana's DB):
#      bash monitoring/export-dashboard.sh

set -e

OUTPUT="monitoring/grafana/dashboards/notification-platform.json"
GRAFANA_URL="http://localhost:3000"
GRAFANA_USER="dev:dev"
DASHBOARD_UID="ndp-overview"

if [ -n "$1" ]; then
  # ── Mode 1: Convert downloaded JSON file ──
  INPUT_FILE="$1"
  echo "📄 Converting downloaded file: $INPUT_FILE"

  python3 -c "
import sys, json

with open(sys.argv[1]) as f:
    data = json.load(f)

# If it's the new scenes format, convert to legacy
if 'elements' in data:
    legacy = {
        'annotations': {'list': []},
        'editable': True,
        'fiscalYearStartMonth': 0,
        'graphTooltip': 1,
        'id': None,
        'links': [],
        'panels': [],
        'refresh': '5s',
        'schemaVersion': 39,
        'tags': data.get('tags', ['notification-platform', 'spring-boot']),
        'templating': {'list': []},
        'time': {'from': 'now-15m', 'to': 'now'},
        'timepicker': {},
        'timezone': 'browser',
        'title': data.get('title', 'Notification Delivery Platform'),
        'uid': 'ndp-overview',
        'version': 1
    }

    # Extract layout info for positioning
    layout = data.get('layout', {}).get('spec', {}).get('rows', [])
    elements = data.get('elements', {})

    panel_id_counter = 100  # for row IDs
    current_y = 0

    for row in layout:
        row_spec = row.get('spec', {})
        row_title = row_spec.get('title', '')

        # Add row panel
        legacy['panels'].append({
            'collapsed': row_spec.get('collapse', False),
            'gridPos': {'h': 1, 'w': 24, 'x': 0, 'y': current_y},
            'id': panel_id_counter,
            'title': row_title,
            'type': 'row'
        })
        panel_id_counter += 1
        current_y += 1

        # Get items from the grid layout
        grid_items = row_spec.get('layout', {}).get('spec', {}).get('items', [])

        for item in grid_items:
            item_spec = item.get('spec', {})
            elem_name = item_spec.get('element', {}).get('name', '')
            elem = elements.get(elem_name, {})
            elem_spec = elem.get('spec', {})

            if not elem_spec:
                continue

            # Build legacy panel
            viz = elem_spec.get('vizConfig', {})
            viz_spec = viz.get('spec', {})
            panel_type = viz.get('group', 'timeseries')

            panel = {
                'title': elem_spec.get('title', ''),
                'description': elem_spec.get('description', ''),
                'type': panel_type,
                'gridPos': {
                    'h': item_spec.get('height', 8),
                    'w': item_spec.get('width', 8),
                    'x': item_spec.get('x', 0),
                    'y': current_y + item_spec.get('y', 0)
                },
                'id': elem_spec.get('id', panel_id_counter),
            }

            # Datasource
            queries_spec = elem_spec.get('data', {}).get('spec', {})
            queries = queries_spec.get('queries', [])
            if queries:
                first_q = queries[0].get('spec', {}).get('query', {})
                ds_name = first_q.get('datasource', {}).get('name', '')
                if ds_name:
                    panel['datasource'] = {'type': 'prometheus', 'uid': ds_name}

            # Field config
            panel['fieldConfig'] = viz_spec.get('fieldConfig', {'defaults': {}, 'overrides': []})

            # Options
            panel['options'] = viz_spec.get('options', {})

            # Targets
            targets = []
            for q in queries:
                q_spec = q.get('spec', {})
                query_inner = q_spec.get('query', {}).get('spec', {})
                t = {'refId': q_spec.get('refId', 'A')}
                t.update(query_inner)
                targets.append(t)
            panel['targets'] = targets

            legacy['panels'].append(panel)
            panel_id_counter = max(panel_id_counter, panel.get('id', 0) + 1)

        # Advance y past this row's content
        if grid_items:
            max_bottom = max(
                (item.get('spec', {}).get('y', 0) + item.get('spec', {}).get('height', 8))
                for item in grid_items
            )
            current_y += max_bottom

    with open(sys.argv[2], 'w') as f:
        json.dump(legacy, f, indent=2)
        f.write('\n')

    print(f'✅ Converted scenes format → legacy format')
else:
    # Already legacy format, just clean up
    data.pop('id', None)
    data.pop('version', None)
    data.setdefault('uid', 'ndp-overview')
    with open(sys.argv[2], 'w') as f:
        json.dump(data, f, indent=2)
        f.write('\n')
    print(f'✅ Already in legacy format, cleaned up')
" "$INPUT_FILE" "$OUTPUT"

else
  # ── Mode 2: Fetch from API ──
  echo "⬇️  Fetching dashboard from Grafana API..."
  curl -s -u "$GRAFANA_USER" "$GRAFANA_URL/api/dashboards/uid/$DASHBOARD_UID" \
    | python3 -c "
import sys, json
data = json.load(sys.stdin)
dash = data['dashboard']
dash.pop('id', None)
dash.pop('version', None)
print(json.dumps(dash, indent=2))
" > "$OUTPUT"
fi

echo "📁 Saved to $OUTPUT ($(wc -l < "$OUTPUT" | tr -d ' ') lines)"
echo "   Now run: docker compose up -d --build grafana"
