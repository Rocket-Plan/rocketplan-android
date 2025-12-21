# QA Emulator Login (RocketPlan Dev)

This doc describes a repeatable adb-driven login flow for the QA environment.

## Prereqs
- Emulator running and visible.
- App installed: `devStandardDebug` flavor.
- adb available on PATH.

## Environment variables
Set these in your shell before running the script:

```
export RP_QA_EMAIL="your.email@example.com"
export RP_QA_PASSWORD="your-password"
```

Optional project defaults (used for project creation):

```
export RP_QA_PROJECT_STREET="2 Codex Way"
export RP_QA_PROJECT_CITY="Cleveland"
export RP_QA_PROJECT_STATE="OH"
export RP_QA_PROJECT_COUNTRY="USA"
export RP_QA_PROJECT_POSTAL="44114"
export RP_QA_STEP_DELAY="2"
export RP_QA_LOGIN_WAIT="2"
export RP_QA_NAV_WAIT="2"
export RP_QA_STOP_AFTER_LOGIN="false"
export RP_QA_PASSWORD_STEP_DELAY="0.2"
```

## Usage
```
./testing/adb_login_qa.sh
```

## Notes
- The script clears the password field and types one character at a time to avoid dropped characters.
- It creates a new project via the manual address flow, selects the Single Unit property type, then deletes it.
- If the UI layout changes, update the tap coordinates in the script.
- The app package for dev flavor is `com.example.rocketplan_android.dev`.
