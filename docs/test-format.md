# Amoo test files

Studio saves reusable test plans as UTF-8 JSON files with the `.amootest` extension. Version 1 is a
presentation-owned interchange format: it describes intent and expectations but does not encode MCP
wire messages or platform-driver commands. When test execution is added to the Studio protocol, Amoo
will translate these steps into its provider-neutral tools.

```json
{
  "formatVersion": 1,
  "name": "Sign in",
  "description": "A returning user can sign in",
  "platform": "Ios",
  "requirements": {
    "appId": "dev.example.app",
    "deviceName": "iPhone 17"
  },
  "steps": [
    {
      "id": "step-1",
      "instruction": "Enter the saved account credentials and tap Sign in",
      "expected": "The account home screen is visible"
    }
  ]
}
```

Readers must reject unsupported major `formatVersion` values. New optional fields may be added within
a version; readers should ignore fields they do not understand. Step IDs are stable within a file and
exist for UI identity and for correlating steps with run progress and reports.

Optional `requirements` describe the app, project, or device needed to run the test. An optional
`compiledPlan` may cache backend-generated tool operations together with its compiler identity and
version; authored steps remain the source of truth. `metadata` is a string map for product-neutral
annotations. All three fields are optional so existing version 1 files remain valid.

New plans use `compiledPlan.toolOperations`, an ordered list of stable operation IDs, Amoo tool names,
and string arguments. Studio validates these operations before saving; Amoo remains responsible for
executing them through the same driver/tool layer used by MCP. The older string `operations` array is
still readable for compatibility but is not used to represent new mobile actions.

Studio's Console and operation editor append validated mobile commands to
`compiledPlan.toolOperations`; the test editor displays the ordered plan and allows individual
operations to be removed. Running a test sends it to Amoo (`tests.start`, or `tests.run` on older
builds) and stays disabled until an executable plan exists, so authored intent is never reported as
if it had run. Saves are atomic: Studio writes a temporary file beside the target and renames it, so
an interrupted save never leaves a truncated `.amootest`.

Provider profiles are not part of `.amootest` files. Studio stores provider endpoints, model names,
and API-key environment-variable names in user preferences. API key values are never written to
preferences or test files; Studio keeps them only in the OS keychain.
