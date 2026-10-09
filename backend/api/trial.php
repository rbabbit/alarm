<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['error' => 'POST required']);
    exit;
}

require __DIR__ . '/config.php';

$input = json_decode(file_get_contents('php://input'), true);
$deviceId = trim((string)($input['device_id'] ?? ''));

if ($deviceId === '' || strlen($deviceId) > 128) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid device_id']);
    exit;
}

try {
    $find = $pdo->prepare(
        'SELECT installed_at, trial_expires_at
         FROM app_trials
         WHERE device_id = ?'
    );
    $find->execute([$deviceId]);
    $trial = $find->fetch();

    if (!$trial) {
        // TEST MODE: five minutes.
        // Production replacement: DATE_ADD(UTC_TIMESTAMP(), INTERVAL 50 DAY)
        $create = $pdo->prepare(
            'INSERT INTO app_trials
             (device_id, installed_at, trial_expires_at)
             VALUES (?, UTC_TIMESTAMP(), DATE_ADD(UTC_TIMESTAMP(), INTERVAL 5 MINUTE))'
        );
        $create->execute([$deviceId]);

        $find->execute([$deviceId]);
        $trial = $find->fetch();
    }

    $expiresAt = new DateTimeImmutable(
        $trial['trial_expires_at'],
        new DateTimeZone('UTC')
    );

    $now = new DateTimeImmutable('now', new DateTimeZone('UTC'));
    $secondsRemaining = max(0, $expiresAt->getTimestamp() - $now->getTimestamp());

    echo json_encode([
        'trial_active' => $secondsRemaining > 0,
        'trial_expires_at' => $expiresAt->format(DateTimeInterface::ATOM),
        'seconds_remaining' => $secondsRemaining,
        'server_time' => $now->format(DateTimeInterface::ATOM)
    ]);

} catch (Throwable $error) {
    error_log($error->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Trial service unavailable']);
}