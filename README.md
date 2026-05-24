# SafeCharge

**SafeCharge** is a premium, anti-theft security system for Android. It protects your device while charging by monitoring for power disconnection and suspicious movement.

## Key Features
- **Theft Detection**: Sounds a high-volume alarm if the charger is unplugged.
- **Motion Detection**: Triggers the alarm if the phone is moved or shaken.
- **Silent Evidence Capture**: Silently captures photos of the thief from both front and back cameras.
- **Remote Alerts**: Sends immediate emails with GPS coordinates and follow-up emails with evidence links.
- **Biometric Unlock**: Securely disarm the alarm using your fingerprint or a fallback PIN.
- **Cyber Dashboard**: A futuristic, neon-accented interface for real-time system monitoring.

## Technical Stack
- **Language**: Java
- **UI Framework**: Material 3
- **Camera**: CameraX API
- **Cloud Evidence**: Cloudinary (Unsigned uploads)
- **Email Service**: EmailJS API
- **Image Loading**: Glide
- **DataBase Storage**: Cloudenary

## Setup Instructions
1. Clone the repository.
2. Create a `local.properties` file in the root directory.
3. Add your credentials (never share these):
   ```properties
   EMAILJS_SERVICE_ID=your_service_id
   EMAILJS_TEMPLATE_ID=your_template_id
   EMAILJS_PUBLIC_KEY=your_public_key
   CLOUDINARY_CLOUD_NAME=your_cloud_name
   CLOUDINARY_UPLOAD_PRESET=your_preset_name
   ```
4. Build and run in Android Studio.
