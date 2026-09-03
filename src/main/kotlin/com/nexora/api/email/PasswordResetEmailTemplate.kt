package com.nexora.api.email

/**
 * Contenido del correo de recuperación de contraseña (B10) — texto plano y
 * HTML se generan juntos para que no se desincronicen. El HTML usa estilos
 * inline y sin CSS externo/flexbox/grid a propósito: es lo que sobrevive
 * consistente entre clientes de correo (Gmail, Outlook, Apple Mail strippean
 * o ignoran `<style>` en `<head>` según el cliente).
 */
data class PasswordResetEmailContent(val text: String, val html: String)

fun passwordResetEmailContent(code: String, ttlMinutes: Long): PasswordResetEmailContent {
    val text = "Tu código es: $code\n\nExpira en $ttlMinutes minutos. Si no pediste esto, ignora este correo."

    val html = """
        <!DOCTYPE html>
        <html lang="es">
          <body style="margin:0; padding:32px 16px; background-color:#f4f4f5; font-family:-apple-system,Helvetica,Arial,sans-serif;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
              <tr>
                <td align="center">
                  <table role="presentation" width="100%" style="max-width:420px;" cellpadding="0" cellspacing="0">
                    <tr>
                      <td style="background-color:#ffffff; border-radius:12px; padding:32px 28px; text-align:center;">
                        <p style="margin:0 0 4px; font-size:15px; font-weight:600; color:#18181b;">Nexora</p>
                        <p style="margin:0 0 24px; font-size:14px; color:#71717a;">Recuperación de contraseña</p>
                        <p style="margin:0 0 8px; font-size:14px; color:#3f3f46;">Tu código de verificación es:</p>
                        <p style="margin:0 0 24px; font-size:36px; font-weight:700; letter-spacing:8px; color:#18181b;">$code</p>
                        <p style="margin:0; font-size:13px; color:#71717a;">Expira en $ttlMinutes minutos.</p>
                        <p style="margin:16px 0 0; font-size:13px; color:#a1a1aa;">Si no pediste esto, ignora este correo — tu contraseña sigue igual.</p>
                      </td>
                    </tr>
                  </table>
                </td>
              </tr>
            </table>
          </body>
        </html>
    """.trimIndent()

    return PasswordResetEmailContent(text, html)
}
