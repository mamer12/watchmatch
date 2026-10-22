package io.watchmatch;

/**
 * Every word of the account screens, in the four languages (Ui.tr: Portuguese, English, Spanish, Arabic). Product names
 * are {app} and {catalog}, filled in by Ui.
 */
final class AccountText {
    private AccountText() {}

    /** The friendly sentence for an AccountRules.E_* code. */
    static String error(String code) {
        switch (code == null ? "" : code) {
            case AccountRules.E_NETWORK:
                return Ui.tr("Sem internet. Verifique a conexão e tente de novo.", "No internet. Check the connection and try again.",
                        "Sin internet. Revisa la conexión e inténtalo de nuevo.", "لا يوجد اتصال بالإنترنت. تحقق من الاتصال وحاول مجدداً.");
            case AccountRules.E_INVALID:
                return Ui.tr("E-mail ou senha incorretos.", "Wrong email or password.", "Correo o contraseña incorrectos.",
                        "البريد الإلكتروني أو كلمة المرور غير صحيحة.");
            case AccountRules.E_TAKEN:
                return Ui.tr("Esse e-mail já tem uma conta. Tente entrar.", "That email already has an account. Try signing in.",
                        "Ese correo ya tiene una cuenta. Prueba a entrar.", "هذا البريد لديه حساب بالفعل. جرّب تسجيل الدخول.");
            case AccountRules.E_WEAK:
                return Ui.tr("Escolha uma senha mais forte, com pelo menos 8 caracteres.", "Choose a stronger password, at least 8 characters.",
                        "Elige una contraseña más fuerte, de al menos 8 caracteres.", "اختر كلمة مرور أقوى من 8 أحرف على الأقل.");
            case AccountRules.E_RATE:
                return Ui.tr("Muitas tentativas. Espere um minuto e tente de novo.", "Too many tries. Wait a minute and try again.",
                        "Demasiados intentos. Espera un minuto e inténtalo de nuevo.", "محاولات كثيرة. انتظر دقيقة وحاول مجدداً.");
            case AccountRules.E_CONFIRM:
                return Ui.tr("O serviço pede para confirmar o e-mail primeiro. Abra o link que enviamos e entre.",
                        "The service wants the email confirmed first. Open the link we sent, then sign in.",
                        "El servicio pide confirmar el correo primero. Abre el enlace que enviamos y entra.",
                        "تطلب الخدمة تأكيد البريد أولاً. افتح الرابط الذي أرسلناه ثم سجّل الدخول.");
            case AccountRules.E_UNAVAILABLE:
                return Ui.tr("Isto ainda não está disponível no serviço.", "This isn't available on the service yet.",
                        "Esto aún no está disponible en el servicio.", "هذا غير متاح في الخدمة بعد.");
            case AccountRules.E_SESSION:
                return Ui.tr("Sua sessão terminou. Entre de novo.", "Your session ended. Sign in again.", "Tu sesión terminó. Entra de nuevo.",
                        "انتهت جلستك. سجّل الدخول من جديد.");
            default:
                return Ui.tr("Algo deu errado. Tente de novo.", "Something went wrong. Try again.", "Algo salió mal. Inténtalo de nuevo.",
                        "حدث خطأ ما. حاول مجدداً.");
        }
    }

    /** What is wrong with the form before it is sent: "email" or "password" (creating: also how long). */
    static String issue(String which, boolean creating) {
        if ("email".equals(which)) {
            return Ui.tr("Digite um e-mail válido.", "Enter a valid email address.", "Escribe un correo válido.", "أدخل بريداً إلكترونياً صالحاً.");
        }
        return creating
                ? Ui.tr("Use pelo menos 8 caracteres na senha.", "Use at least 8 characters for the password.",
                        "Usa al menos 8 caracteres en la contraseña.", "استخدم 8 أحرف على الأقل لكلمة المرور.")
                : Ui.tr("Digite a senha.", "Enter the password.", "Escribe la contraseña.", "أدخل كلمة المرور.");
    }

    static String signIn() {
        return Ui.tr("Entrar", "Sign in", "Entrar", "تسجيل الدخول");
    }

    static String create() {
        return Ui.tr("Criar conta", "Create account", "Crear cuenta", "إنشاء حساب");
    }

    static String guest() {
        return Ui.tr("Continuar como visitante", "Continue as guest", "Continuar como invitado", "المتابعة كضيف");
    }

    static String cancel() {
        return Ui.tr("Cancelar", "Cancel", "Cancelar", "إلغاء");
    }

    static String account() {
        return Ui.tr("Conta", "Account", "Cuenta", "الحساب");
    }

    static String status(String email) {
        return email.isEmpty() ? Ui.tr("Visitante", "Guest", "Invitado", "ضيف")
                : Ui.tr("Conectado como " + Ui.isolate(email), "Signed in as " + Ui.isolate(email),
                        "Conectado como " + Ui.isolate(email), "مسجّل الدخول باسم " + Ui.isolate(email));
    }

    static String site() {
        return Config.SITE_URL.replaceFirst("^https?://", "").replaceAll("/+$", "");
    }
}
