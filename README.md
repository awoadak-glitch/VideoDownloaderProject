# AWR Download Next 2.0

Standalone Windows download manager. This branch is independent of the original main branch.

- Arabic native WinForms interface, HTTP/HTTPS downloads and 1–16 connections.
- Pause/resume with persisted aria2 maps and remote identity checks.
- Retry interrupted transfers, queue, scheduling, global rate cap, link refresh.
- Check length and SHA-256; verify against a supplied reference before publishing a file.
- Existing-file protection, HTML rejection and Windows-safe filenames.

Run build-windows.ps1 on Windows with .NET Framework 4.8 and Python for the integration fixtures. GitHub Actions compiles and tests the application on windows-2022. Engine archive SHA-256 is pinned. The separate aria2 binary and its license remain unmodified.

This first release covers direct HTTP/HTTPS file links. Browser integration and automatic video/HLS/DASH detection are not included. Server limits and network bandwidth still control download speed. Calculating a hash without a trusted reference does not prove authenticity. Scheduling requires an awake computer and the app running.

Source files: Core.cs, UI.cs, Tests.cs, fixture.py. No credentials or user download history are included.
