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
exist for UI identity and future report correlation.

Optional `requirements` describe the app, project, or device needed to run the test. An optional
`compiledPlan` may cache backend-generated tool operations together with its compiler identity and
version; authored steps remain the source of truth. `metadata` is a string map for product-neutral
annotations. All three fields are optional so existing version 1 files remain valid.

Provider profiles are not part of `.amootest` files. Studio stores provider endpoints, model names,
and API-key environment-variable names in user preferences. API key values are never written by Studio.
