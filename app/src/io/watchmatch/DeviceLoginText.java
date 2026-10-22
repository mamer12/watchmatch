package io.watchmatch;

import android.content.Context;

/** Every word of "Sign in with your phone", in the four languages (Ui.tr: Portuguese, English, Spanish, Arabic). */
final class DeviceLoginText {
    private DeviceLoginText() {}

    static String phone() {
        return Ui.tr("Entrar com o celular", "Sign in with your phone", "Entrar con tu móvil", "تسجيل الدخول بهاتفك");
    }

    static String emailPassword() {
        return Ui.tr("Usar e-mail e senha", "Use email and password", "Usar correo y contraseña", "استخدام البريد وكلمة المرور");
    }

    static String title() {
        return Ui.tr("Entre nesta TV com o celular", "Sign in this TV with your phone", "Inicia sesión en este TV con tu móvil",
                "سجّل الدخول على هذا التلفاز بهاتفك");
    }

    static String[] steps() {
        return new String[] {
            "1   " + Ui.tr("Aponte a câmera do celular para o QR code", "Scan with your phone", "Escanea con tu móvil", "امسح الرمز بهاتفك"),
            "2   " + Ui.tr("Entre ou crie sua conta", "Sign in or create your account", "Inicia sesión o crea tu cuenta", "سجّل الدخول أو أنشئ حسابك"),
            "3   " + Ui.tr("Toque em Aprovar", "Tap Approve", "Toca Aprobar", "اضغط على موافقة")};
    }

    static String gettingCode() {
        return Ui.tr("Gerando o código…", "Getting a code…", "Obteniendo un código…", "جارٍ إنشاء الرمز…");
    }

    static String expiresIn(String clock) {
        String t = Ui.digits(clock);
        return Ui.tr("O código expira em " + t, "The code expires in " + t, "El código caduca en " + t, "ينتهي الرمز خلال " + t);
    }

    static String signingIn() {
        return Ui.tr("Entrando…", "Signing you in…", "Iniciando tu sesión…", "جارٍ تسجيل دخولك…");
    }

    static String offline() {
        return Ui.tr("Sem internet. Continuo tentando…", "No internet. Still trying…", "Sin internet. Sigo intentándolo…",
                "لا يوجد اتصال. ما زلنا نحاول…");
    }

    static String retry() {
        return Ui.tr("Tentar de novo", "Try again", "Reintentar", "إعادة المحاولة");
    }

    /** The sentence for a failed request, poll or link (rate limit: how long to wait). */
    static String error(String code) {
        if (AccountRules.E_RATE.equals(code)) {
            return Ui.tr("Códigos demais por enquanto. Espere alguns minutos e tente de novo.",
                    "Too many codes for now. Wait a few minutes, then try again.",
                    "Demasiados códigos por ahora. Espera unos minutos e inténtalo de nuevo.",
                    "رموز كثيرة حالياً. انتظر بضع دقائق ثم حاول مجدداً.");
        }
        return AccountText.error(code);
    }

    static String linked() {
        return Ui.tr("Vinculada à sua conta", "Linked to your account", "Vinculado a tu cuenta", "مرتبط بحسابك");
    }

    static String signOutTv() {
        return Ui.tr("Sair desta TV", "Sign out of this TV", "Cerrar sesión en este TV", "تسجيل الخروج من هذا التلفاز");
    }

    static String linkOther() {
        return Ui.tr("Vincular a outra conta", "Link to another account", "Vincular a otra cuenta", "الربط بحساب آخر");
    }

    static String manage() {
        String site = Ui.isolate(AccountText.site());
        return Ui.tr("Gerencie sua conta no celular ou em " + site, "Manage your account on your phone or at " + site,
                "Gestiona tu cuenta en tu móvil o en " + site, "أدِر حسابك على هاتفك أو على " + site);
    }

    static String phoneHelp() {
        return Ui.tr("Escaneie um QR code com o celular e aprove: sem digitar e-mail nem senha na TV.",
                "Scan a QR code with your phone and approve: no typing an email or password on the TV.",
                "Escanea un código QR con tu móvil y aprueba: sin escribir correo ni contraseña en el TV.",
                "امسح رمز QR بهاتفك ووافق: دون كتابة بريد أو كلمة مرور على التلفاز.");
    }

    static String welcomeLine() {
        return Ui.tr("Entre uma vez e seus perfis e progresso aparecem em todos os aparelhos.",
                "Sign in once and your profiles and progress show up on every device.",
                "Inicia sesión una vez y tus perfiles y progreso aparecen en todos tus dispositivos.",
                "سجّل الدخول مرة واحدة وستظهر ملفاتك وتقدمك على كل جهاز.");
    }

    /** Settings, Account's line: "Linked to your account", "Signed in as …" or "Guest". */
    static String status(Context c) {
        return Account.linkedByPhone(c) ? linked() : AccountText.status(Account.signedInEmail(c));
    }
}
