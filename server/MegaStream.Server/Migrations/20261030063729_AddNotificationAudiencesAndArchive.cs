using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace MegaStream.Server.Migrations
{
    public partial class AddNotificationAudiencesAndArchive : Migration
    {
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTime>(
                name: "ArchivedAt",
                table: "AdminNotifications",
                type: "datetime(6)",
                nullable: true);

            migrationBuilder.AddColumn<Guid>(
                name: "TargetLicenseId",
                table: "AdminNotifications",
                type: "char(36)",
                nullable: true,
                collation: "ascii_general_ci");

            migrationBuilder.CreateIndex(
                name: "IX_AdminNotifications_TargetLicenseId_CreatedAt",
                table: "AdminNotifications",
                columns: new[] { "TargetLicenseId", "CreatedAt" });

            migrationBuilder.AddForeignKey(
                name: "FK_AdminNotifications_Licenses_TargetLicenseId",
                table: "AdminNotifications",
                column: "TargetLicenseId",
                principalTable: "Licenses",
                principalColumn: "Id",
                onDelete: ReferentialAction.Restrict);

            migrationBuilder.AddCheckConstraint(
                name: "CK_AdminNotifications_OneTarget",
                table: "AdminNotifications",
                sql: "TargetInstallationId IS NULL OR TargetLicenseId IS NULL");
        }

        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropCheckConstraint(
                name: "CK_AdminNotifications_OneTarget",
                table: "AdminNotifications");

            migrationBuilder.DropForeignKey(
                name: "FK_AdminNotifications_Licenses_TargetLicenseId",
                table: "AdminNotifications");

            migrationBuilder.DropIndex(
                name: "IX_AdminNotifications_TargetLicenseId_CreatedAt",
                table: "AdminNotifications");

            migrationBuilder.DropColumn(name: "TargetLicenseId", table: "AdminNotifications");
            migrationBuilder.DropColumn(name: "ArchivedAt", table: "AdminNotifications");
        }
    }
}
